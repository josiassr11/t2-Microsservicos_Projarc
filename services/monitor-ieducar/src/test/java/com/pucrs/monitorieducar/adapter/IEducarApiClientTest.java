package com.pucrs.monitorieducar.adapter;

import com.pucrs.monitorieducar.adapter.out.ieducar.IEducarApiClient;
import com.pucrs.monitorieducar.adapter.out.ieducar.Resiliencia;
import com.pucrs.monitorieducar.config.IEducarProperties;
import com.pucrs.monitorieducar.config.ResilienciaProperties;
import com.pucrs.monitorieducar.domain.exception.SistemaIndisponivelException;
import com.pucrs.monitorieducar.domain.model.Matricula;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.VinculoProfessor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Lê as respostas REAIS do iEducar capturadas no T1 (src/test/resources/samples) e confere o mapeamento. */
class IEducarApiClientTest {

    MockRestServiceServer server;
    IEducarApiClient client;
    IEducarProperties props;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ieducar");
        server = MockRestServiceServer.bindTo(builder).build();
        props = new IEducarProperties("http://ieducar", "tok", "chave", "segredo", 1);
        client = new IEducarApiClient(builder.build(), props, Resiliencia.semProtecao());
    }

    static String amostra(String nome) throws IOException {
        return new ClassPathResource("samples/" + nome).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void listaTurmasComCursoEEscola() throws IOException {
        server.expect(requestTo(containsString("/api/school-class?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(amostra("ieducar-school-class.json"), MediaType.APPLICATION_JSON));

        List<Turma> turmas = client.listarTurmas();

        assertThat(turmas).hasSize(4);
        Turma primeira = turmas.get(0);
        assertThat(primeira.id()).isEqualTo(3);
        assertThat(primeira.nome()).isEqualTo("Turma A");
        assertThat(primeira.ano()).isEqualTo(2026);
        assertThat(primeira.ativa()).isTrue();
        assertThat(primeira.curso().nome()).isEqualTo("Engenharia de Software");
        assertThat(primeira.escolaId()).isEqualTo(2);
        assertThat(primeira.escolaNome()).isEqualTo("Escola de Tecnologia - Campus Central");
        server.verify();
    }

    @Test
    void listaMatriculasPorEnturmacao() throws IOException {
        server.expect(requestTo(containsString("/api/registration?year_eq=2026")))
                .andRespond(withSuccess(amostra("ieducar-registration-ano.json"), MediaType.APPLICATION_JSON));

        List<Matricula> matriculas = client.listarMatriculas(2026);

        assertThat(matriculas).hasSize(10).allMatch(Matricula::ativa);
        assertThat(matriculas.get(0).aluno().id()).isEqualTo(3);
        assertThat(matriculas.get(0).aluno().nome()).isEqualTo("Ana Beatriz Souza");
        assertThat(matriculas.get(0).aluno().email()).isEqualTo("ana.souza@aluno.demo.edu.br");
        assertThat(matriculas.get(0).turmaId()).isEqualTo(3);
    }

    @Test
    void enturmacaoInativaViraMatriculaInativa_mesmoComAtivoNaRaiz() throws IOException {
        server.expect(requestTo(containsString("/api/registration")))
                .andRespond(withSuccess(amostra("ieducar-cancelamento-registration-ano.json"), MediaType.APPLICATION_JSON));

        List<Matricula> matriculas = client.listarMatriculas(2026);

        assertThat(matriculas.stream().filter(m -> !m.ativa()).map(m -> m.aluno().id())).containsExactly(4L, 5L);
    }

    @Test
    void listaVinculosDeProfessoresEBuscaOsDadosDeCadaServidor() throws IOException {
        server.expect(requestTo(containsString("/module/Api/Servidor?oper=get")))
                .andExpect(requestTo(containsString("resource=servidores-disciplinas-turmas")))
                .andExpect(requestTo(containsString("access_key=chave")))
                .andRespond(withSuccess(amostra("ieducar-legacy-vinculos-professor-turma.json"), MediaType.APPLICATION_JSON));
        server.expect(times(4), requestTo(containsString("resource=dados-servidor")))
                .andRespond(withSuccess(amostra("ieducar-legacy-dados-servidor.json"), MediaType.APPLICATION_JSON));

        List<VinculoProfessor> vinculos = client.listarVinculosProfessores(2026, List.of(2L));

        assertThat(vinculos).hasSize(4).allMatch(VinculoProfessor::ativo);
        assertThat(vinculos.get(0).turmaId()).isEqualTo(3);
        assertThat(vinculos.get(0).professor().email()).isEqualTo("marcos.ribeiro@prof.demo.edu.br");
        server.verify();
    }

    @Test
    void vinculoComDeletedAtEhInativo() throws IOException {
        server.expect(requestTo(containsString("servidores-disciplinas-turmas")))
                .andRespond(withSuccess(amostra("ieducar-cancelamento-legacy-vinculos.json"), MediaType.APPLICATION_JSON));
        server.expect(times(4), requestTo(containsString("dados-servidor")))
                .andRespond(withSuccess(amostra("ieducar-legacy-dados-servidor.json"), MediaType.APPLICATION_JSON));

        List<VinculoProfessor> vinculos = client.listarVinculosProfessores(2026, List.of(2L));

        assertThat(vinculos.stream().filter(v -> !v.ativo()).map(VinculoProfessor::turmaId))
                .containsExactlyInAnyOrder(6L, 4L);
    }

    @Test
    void chaveLegadaInvalida_apesarDoHttp200_viraIndisponibilidade() throws IOException {
        server.expect(requestTo(containsString("servidores-disciplinas-turmas")))
                .andRespond(withSuccess(amostra("ieducar-legacy-chave-invalida.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.listarVinculosProfessores(2026, List.of(2L)))
                .isInstanceOf(SistemaIndisponivelException.class)
                .hasMessageContaining("Chave de acesso inválida");
    }

    @Test
    void http401ViraIndisponibilidade() {
        server.expect(requestTo(containsString("/api/school-class")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Unauthenticated.\"}"));

        assertThatThrownBy(() -> client.listarTurmas()).isInstanceOf(SistemaIndisponivelException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void percorreTodasAsPaginas() {
        String p1 = "{\"data\":[{\"cod_turma\":1,\"nm_turma\":\"A\",\"ano\":2026,\"ativo\":1,\"ref_cod_curso\":1,\"course\":{\"id\":1,\"name\":\"C\"}}],\"links\":{\"next\":\"http://x?page=2\"}}";
        String p2 = "{\"data\":[{\"cod_turma\":2,\"nm_turma\":\"B\",\"ano\":2026,\"ativo\":1,\"ref_cod_curso\":1,\"course\":{\"id\":1,\"name\":\"C\"}}],\"links\":{\"next\":null}}";
        server.expect(requestTo(containsString("page=1"))).andRespond(withSuccess(p1, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("page=2"))).andRespond(withSuccess(p2, MediaType.APPLICATION_JSON));

        assertThat(client.listarTurmas()).extracting(Turma::id).containsExactly(1L, 2L);
        server.verify();
    }

    @Test
    void comRetry_falhaTransitoriaEhRepetidaERecuperada() throws IOException {
        var res = new ResilienciaProperties(Duration.ofSeconds(1), Duration.ofSeconds(1),
                new ResilienciaProperties.Retry(3, Duration.ofMillis(5), 2.0),
                new ResilienciaProperties.Circuito(10, 10, 50, Duration.ofSeconds(30), 2));
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ieducar");
        MockRestServiceServer srv = MockRestServiceServer.bindTo(builder).build();
        var comRetry = new IEducarApiClient(builder.build(), props,
                Resiliencia.criar("iEducar", res, CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults()));
        srv.expect(requestTo(containsString("/api/school-class"))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        srv.expect(requestTo(containsString("/api/school-class"))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        srv.expect(requestTo(containsString("/api/school-class")))
                .andRespond(withSuccess(amostra("ieducar-school-class.json"), MediaType.APPLICATION_JSON));

        assertThat(comRetry.listarTurmas()).hasSize(4);
        srv.verify();   // as 3 requisições aconteceram: 2 falhas + 1 sucesso
    }
}
