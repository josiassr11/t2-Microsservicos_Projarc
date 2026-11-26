package com.pucrs.monitorieducar.adapter.out.ieducar;

import com.pucrs.monitorieducar.config.IEducarProperties;
import com.pucrs.monitorieducar.domain.exception.SistemaExternoException;
import com.pucrs.monitorieducar.domain.exception.SistemaIndisponivelException;
import com.pucrs.monitorieducar.domain.model.Aluno;
import com.pucrs.monitorieducar.domain.model.Curso;
import com.pucrs.monitorieducar.domain.model.Matricula;
import com.pucrs.monitorieducar.domain.model.Professor;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.VinculoProfessor;
import com.pucrs.monitorieducar.domain.port.out.IEducarPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapter de saída: lê turmas, matrículas e professores do iEducar (API REST com Bearer + API legada de
 * servidores). Cada chamada HTTP passa por {@link Resiliencia} (retry + circuit breaker) e é registrada no log
 * com a etiqueta {@code [HTTP-OUT]} — sem token nem chaves.
 */
public class IEducarApiClient implements IEducarPort {

    private static final Logger log = LoggerFactory.getLogger(IEducarApiClient.class);
    private static final String SISTEMA = "iEducar";
    private static final int PAGINA = 100;

    /** Situações de matrícula que encerram a enturmação (aprovado/reprovado/transferido/abandono...). */
    private static final Set<Integer> SITUACOES_ENCERRADAS = Set.of(4, 5, 6, 15);

    private final RestClient http;
    private final IEducarProperties props;
    private final Resiliencia resiliencia;
    private final JsonMapper json = JsonMapper.builder().build();

    public IEducarApiClient(RestClient http, IEducarProperties props, Resiliencia resiliencia) {
        this.http = http;
        this.props = props;
        this.resiliencia = resiliencia;
    }

    /* ---------------- turmas ---------------- */

    @Override
    public List<Turma> listarTurmas() {
        List<Turma> turmas = new ArrayList<>();
        for (int pagina = 1; ; pagina++) {
            JsonNode raiz = getRest("/api/school-class", pagina,
                    "/api/school-class?only={only}&include={include}&order={order}&show={show}&page={page}",
                    Map.of("only", "cod_turma,nm_turma,sgl_turma,ano,ativo,visivel,ref_cod_curso,ref_ref_cod_escola,ref_ref_cod_serie",
                            "include", "course:cod_curso,nm_curso,ativo|school.person:idpes,nome",
                            "order", "cod_turma", "show", PAGINA, "page", pagina));
            for (JsonNode t : raiz.path("data")) {
                turmas.add(paraTurma(t));
            }
            if (!temProximaPagina(raiz)) {
                return turmas;
            }
        }
    }

    private Turma paraTurma(JsonNode t) {
        long cursoId = t.path("course").path("id").asLong(t.path("ref_cod_curso").asLong());
        String cursoNome = texto(t.path("course"), "name");
        Curso curso = new Curso(cursoId, cursoNome != null ? cursoNome : "Curso " + cursoId,
                t.path("course").path("ativo").asInt(1) == 1);
        long escolaId = t.path("school").path("id").asLong(t.path("ref_ref_cod_escola").asLong());
        String escolaNome = texto(t.path("school").path("person"), "name");
        return new Turma(t.path("cod_turma").asLong(), texto(t, "nm_turma"), t.path("ano").asInt(),
                t.path("ativo").asInt() == 1, escolaId, escolaNome, curso);
    }

    /* ---------------- matrículas ---------------- */

    @Override
    public List<Matricula> listarMatriculas(int ano) {
        List<Matricula> matriculas = new ArrayList<>();
        for (int pagina = 1; ; pagina++) {
            JsonNode raiz = getRest("/api/registration (ano=" + ano + ")", pagina,
                    "/api/registration?year_eq={ano}&show={show}&page={page}&include={include}",
                    Map.of("ano", ano, "show", PAGINA, "page", pagina,
                            "include", "student.individual:idpes,cpf|enrollments:ref_cod_matricula,ref_cod_turma,sequencial,ativo,data_enturmacao"));
            for (JsonNode r : raiz.path("data")) {
                Aluno aluno = new Aluno(r.path("student_id").asLong(), texto(r, "nome"), texto(r, "email"),
                        texto(r.path("student").path("individual"), "cpf"));
                boolean situacaoAtiva = !SITUACOES_ENCERRADAS.contains(r.path("aprovado").asInt());
                // O "ativo" da raiz NÃO é o da matrícula: vale enrollments[].ativo
                for (JsonNode e : r.path("enrollments")) {
                    matriculas.add(new Matricula(aluno, e.path("ref_cod_turma").asLong(),
                            situacaoAtiva && e.path("ativo").asInt() == 1));
                }
            }
            if (!temProximaPagina(raiz)) {
                return matriculas;
            }
        }
    }

    /* ---------------- professores ---------------- */

    @Override
    public List<VinculoProfessor> listarVinculosProfessores(int ano, Collection<Long> escolaIds) {
        if (escolaIds.isEmpty()) {
            return List.of();
        }
        JsonNode raiz = getLegado("servidores-disciplinas-turmas (ano=" + ano + ")",
                Map.of("resource", "servidores-disciplinas-turmas", "instituicao_id", props.instituicaoId(),
                        "ano", ano,
                        "escola", escolaIds.stream().map(String::valueOf).collect(Collectors.joining(","))));
        Map<Long, Professor> professores = new HashMap<>();
        List<VinculoProfessor> vinculos = new ArrayList<>();
        Set<Long> vistos = new LinkedHashSet<>();
        for (JsonNode v : raiz.path("vinculos")) {
            long servidorId = v.path("servidor_id").asLong();
            vistos.add(servidorId);
            Professor professor = professores.computeIfAbsent(servidorId, this::buscarProfessor);
            boolean ativo = v.path("deleted_at").isNull() || v.path("deleted_at").isMissingNode();
            vinculos.add(new VinculoProfessor(professor, v.path("turma_id").asLong(), ativo));
        }
        return vinculos;
    }

    private Professor buscarProfessor(long servidorId) {
        try {
            JsonNode r = getLegado("dados-servidor (servidor_id=" + servidorId + ")",
                    Map.of("resource", "dados-servidor", "servidor_id", servidorId)).path("result");
            return new Professor(servidorId, texto(r, "nome"), texto(r, "email"), texto(r, "cpf"));
        } catch (SistemaIndisponivelException e) {
            throw e;
        } catch (SistemaExternoException e) {
            log.warn("[HTTP-OUT] iEducar: não foi possível ler os dados do servidor {}: {}", servidorId, e.getMessage());
            return new Professor(servidorId, "Servidor " + servidorId, null, null);
        }
    }

    /* ---------------- http / json ---------------- */

    private JsonNode getRest(String rotulo, int pagina, String template, Map<String, ?> variaveis) {
        String descricao = "GET " + rotulo + " página=" + pagina;
        String corpo = chamar(descricao, () -> http.get().uri(template, variaveis)
                .header("Authorization", "Bearer " + props.token())
                .header("Accept", "application/json")
                .retrieve().toEntity(String.class));
        return lerJson(corpo);
    }

    /** API legada: HTTP 200 mesmo em erro; o erro vem em {@code any_error_msg}. */
    private JsonNode getLegado(String rotulo, Map<String, ?> params) {
        Map<String, Object> todos = new HashMap<>(params);
        todos.put("access_key", props.accessKey());
        todos.put("secret_key", props.secretKey());
        StringBuilder uri = new StringBuilder("/module/Api/Servidor?oper=get");
        todos.keySet().forEach(k -> uri.append('&').append(k).append("={").append(k).append('}'));
        // o rótulo do log nunca inclui a query (que carrega access_key/secret_key)
        String corpo = chamar("GET /module/Api/Servidor " + rotulo, () -> http.get().uri(uri.toString(), todos)
                .header("Accept", "application/json")
                .retrieve().toEntity(String.class));
        JsonNode raiz = lerJson(corpo);
        if (raiz.path("any_error_msg").asBoolean(false)) {
            String msg = raiz.path("msgs").isEmpty() ? "erro desconhecido" : texto(raiz.path("msgs").get(0), "msg");
            throw new SistemaIndisponivelException(SISTEMA, "API legada recusou a chamada: " + msg);
        }
        return raiz;
    }

    /** Uma chamada HTTP protegida (retry + circuito) e registrada no log. */
    private String chamar(String descricao, java.util.function.Supplier<ResponseEntity<String>> requisicao) {
        return resiliencia.executar(() -> {
            long inicio = System.nanoTime();
            log.info("[HTTP-OUT] iEducar {}", descricao);
            try {
                ResponseEntity<String> resposta = requisicao.get();
                log.info("[HTTP-OUT] iEducar {} -> {} em {}ms", descricao, resposta.getStatusCode().value(),
                        ms(inicio));
                return resposta.getBody();
            } catch (RestClientResponseException e) {
                log.warn("[HTTP-OUT] iEducar {} -> HTTP {} em {}ms", descricao, e.getStatusCode().value(), ms(inicio));
                throw new SistemaIndisponivelException(SISTEMA, "HTTP " + e.getStatusCode().value() + " em " + descricao, e);
            } catch (RestClientException e) {
                String causa = causa(e);
                log.warn("[HTTP-OUT] iEducar {} -> sem resposta ({}) em {}ms", descricao, causa, ms(inicio));
                throw new SistemaIndisponivelException(SISTEMA, "sem resposta em " + descricao + " (" + causa + ")", e);
            }
        });
    }

    private JsonNode lerJson(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            throw new SistemaExternoException(SISTEMA, "Resposta vazia do iEducar");
        }
        try {
            return json.readTree(corpo);
        } catch (JacksonException e) {
            throw new SistemaExternoException(SISTEMA, "Resposta inválida do iEducar: " + e.getOriginalMessage(), e);
        }
    }

    /** Causa raiz legível: "ConnectException: Connection refused" em vez de "I/O error ...: null". */
    private static String causa(Throwable e) {
        Throwable raiz = e;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        String msg = raiz.getMessage();
        return raiz.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    private static long ms(long inicioNanos) {
        return (System.nanoTime() - inicioNanos) / 1_000_000;
    }

    private static boolean temProximaPagina(JsonNode raiz) {
        JsonNode proxima = raiz.path("links").path("next");
        if (!proxima.isMissingNode()) {
            return !proxima.isNull();
        }
        return raiz.path("meta").path("current_page").asInt(1) < raiz.path("meta").path("last_page").asInt(1);
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asString();
    }
}
