package com.pucrs.publicadormoodle.application;

import com.pucrs.publicadormoodle.domain.exception.MoodleException;
import com.pucrs.publicadormoodle.domain.exception.SistemaExternoException;
import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import com.pucrs.publicadormoodle.domain.model.CursoMoodle;
import com.pucrs.publicadormoodle.domain.model.Mapeamento;
import com.pucrs.publicadormoodle.domain.model.Mapeamento.Tipo;
import com.pucrs.publicadormoodle.domain.model.NovoUsuarioMoodle;
import com.pucrs.publicadormoodle.domain.model.Participante;
import com.pucrs.publicadormoodle.domain.model.ResultadoSincronizacao;
import com.pucrs.publicadormoodle.domain.model.Resumo;
import com.pucrs.publicadormoodle.domain.model.StatusSincronizacao;
import com.pucrs.publicadormoodle.domain.model.TurmaComando;
import com.pucrs.publicadormoodle.domain.model.TurmaDados;
import com.pucrs.publicadormoodle.domain.model.UsuarioMoodle;
import com.pucrs.publicadormoodle.domain.port.out.MoodlePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Aplica UMA turma no Moodle (mesmas regras do T1, agora por comando):
 * <ol>
 *   <li>o <b>curso do iEducar</b> vira uma <b>categoria</b> (buscada pelo idnumber antes de criar);</li>
 *   <li>a <b>turma</b> vira um <b>curso</b> dentro da categoria (criado, renomeado ou oculto);</li>
 *   <li>cada <b>aluno</b>/<b>professor</b> vira usuário (buscado pelo idnumber) inscrito como estudante/professor;</li>
 *   <li>participante inativo <b>suspende</b> a inscrição — nada é apagado.</li>
 * </ol>
 * Repetir o mesmo comando nunca duplica nada (idempotência por {@code idnumber}). Falha de negócio de um item vai
 * para {@code erros} (status PARCIAL); {@link SistemaIndisponivelException} sobe para a política de retry de mensagem.
 */
@Service
public class SincronizadorTurma {

    public static final String PREFIXO_CURSO = "IEDUCAR-CURSO-";
    public static final String PREFIXO_TURMA = "IEDUCAR-TURMA-";
    public static final String PREFIXO_ALUNO = "IEDUCAR-ALUNO-";
    public static final String PREFIXO_SERVIDOR = "IEDUCAR-SERVIDOR-";

    private static final Logger log = LoggerFactory.getLogger(SincronizadorTurma.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final MoodlePort moodle;
    private final OpcoesMoodle opcoes;

    public SincronizadorTurma(MoodlePort moodle, OpcoesMoodle opcoes) {
        this.moodle = moodle;
        this.opcoes = opcoes;
    }

    /** Devolve o resultado com {@code tentativas=1} (o processador ajusta). */
    public ResultadoSincronizacao aplicar(TurmaComando comando) {
        Execucao ex = new Execucao();
        TurmaDados turma = comando.turma();
        Long categoriaId = null;
        Long cursoId = null;
        boolean falhaNoCurso = false;
        try {
            String idNumberCurso = PREFIXO_TURMA + turma.id();
            Optional<CursoMoodle> existente = moodle.buscarCurso(idNumberCurso);
            if (existente.isPresent()) {
                CursoMoodle curso = existente.get();
                cursoId = curso.id();
                categoriaId = curso.categoriaId() > 0 ? curso.categoriaId() : null;
                atualizarCursoSeNecessario(turma, curso, ex);
            } else if (!turma.ativa()) {
                log.info("[PROCESSA] turmaId={} inativa e sem curso no Moodle: nada a fazer", turma.id());
                return ex.concluir(comando, null, null);
            } else {
                categoriaId = garantirCategoria(turma, ex);
                cursoId = moodle.criarCurso(turma.nomeNoMoodle(), "TURMA-" + turma.id(), idNumberCurso, categoriaId, true);
                ex.resumo.cursosCriados++;
                log.info("[PROCESSA] turmaId={} curso criado no Moodle (id={}, idnumber={})", turma.id(), cursoId, idNumberCurso);
            }
            if (categoriaId != null) {
                ex.mapeamentos.add(new Mapeamento(Tipo.CATEGORIA, turma.cursoId(), categoriaId, PREFIXO_CURSO + turma.cursoId()));
            }
            ex.mapeamentos.add(new Mapeamento(Tipo.CURSO, turma.id(), cursoId, idNumberCurso));
        } catch (SistemaIndisponivelException e) {
            throw e;
        } catch (SistemaExternoException e) {
            ex.erro("Turma " + turma.id() + " (" + turma.nome() + ")", e);
            falhaNoCurso = true;
        }
        if (falhaNoCurso) {
            return ex.falha(comando);
        }
        if (turma.ativa()) {
            sincronizarParticipantes(comando, cursoId, ex);
        }
        return ex.concluir(comando, categoriaId, cursoId);
    }

    private void atualizarCursoSeNecessario(TurmaDados turma, CursoMoodle curso, Execucao ex) {
        String nome = turma.nomeNoMoodle();
        boolean mudouNome = !nome.equals(curso.nomeCompleto());
        boolean mudouVisibilidade = curso.visivel() != turma.ativa();
        if (mudouNome || mudouVisibilidade) {
            moodle.atualizarCurso(curso.id(), nome, turma.ativa());
            if (mudouVisibilidade && !turma.ativa()) {
                ex.resumo.cursosOcultados++;
                log.info("[PROCESSA] turmaId={} inativa no iEducar: curso {} ocultado no Moodle", turma.id(), curso.id());
            } else {
                ex.resumo.cursosAtualizados++;
                log.info("[PROCESSA] turmaId={} curso {} atualizado no Moodle (nome/visibilidade)", turma.id(), curso.id());
            }
        }
    }

    private long garantirCategoria(TurmaDados turma, Execucao ex) {
        String idNumber = PREFIXO_CURSO + turma.cursoId();
        return moodle.buscarCategoria(idNumber).orElseGet(() -> {
            long id = moodle.criarCategoria(turma.cursoNome(), idNumber);
            log.info("[PROCESSA] categoria criada no Moodle (id={}, idnumber={})", id, idNumber);
            return id;
        });
    }

    private void sincronizarParticipantes(TurmaComando comando, long cursoId, Execucao ex) {
        long turmaId = comando.turma().id();
        Set<Long> ativos;
        try {
            ativos = moodle.listarInscritosAtivos(cursoId);
        } catch (SistemaIndisponivelException e) {
            throw e;
        } catch (SistemaExternoException e) {
            ex.erro("Turma " + turmaId + " (inscrições)", e);
            return;
        }
        for (Participante p : comando.alunos()) {
            participante(turmaId, cursoId, ativos, p, false, ex);
        }
        for (Participante p : comando.professores()) {
            participante(turmaId, cursoId, ativos, p, true, ex);
        }
    }

    private void participante(long turmaId, long cursoId, Set<Long> ativos, Participante p, boolean professor, Execucao ex) {
        try {
            sincronizarParticipante(p, professor, cursoId, ativos, ex);
        } catch (SistemaIndisponivelException e) {
            throw e;
        } catch (SistemaExternoException e) {
            ex.erro("Turma " + turmaId + " / " + (professor ? "Professor " : "Aluno ") + p.id() + " (" + p.nome() + ")", e);
        }
    }

    private void sincronizarParticipante(Participante p, boolean professor, long cursoId, Set<Long> ativos, Execucao ex) {
        String idNumber = (professor ? PREFIXO_SERVIDOR : PREFIXO_ALUNO) + p.id();
        UsuarioMoodle usuario = moodle.buscarUsuario(idNumber).orElse(null);
        if (usuario != null) {
            usuario = atualizarSeNecessario(usuario, p, professor, ex);
        } else if (p.ativo()) {
            usuario = criarUsuario(p, professor, idNumber);
            ex.resumo.usuariosCriados++;
        } else {
            return;
        }
        ex.mapeamentos.add(new Mapeamento(professor ? Tipo.PROFESSOR : Tipo.ALUNO, p.id(), usuario.id(), idNumber));

        long papel = professor ? opcoes.papelProfessorId() : opcoes.papelAlunoId();
        boolean inscritoAtivo = ativos.contains(usuario.id());
        if (p.ativo() && !inscritoAtivo) {
            moodle.inscrever(cursoId, usuario.id(), papel, false);
            ex.resumo.inscricoesCriadas++;
        } else if (!p.ativo() && inscritoAtivo) {
            moodle.inscrever(cursoId, usuario.id(), papel, true);
            ex.resumo.inscricoesSuspensas++;
        }
    }

    private UsuarioMoodle criarUsuario(Participante p, boolean professor, String idNumber) {
        String username = (professor ? "prof" : "aluno") + p.id();
        String[] nome = dividirNome(p.nome());
        String emailReal = emailValido(p.email()) ? p.email().trim() : null;
        String emailPadrao = username + "@" + opcoes.dominioEmail();
        try {
            long id = moodle.criarUsuario(new NovoUsuarioMoodle(username, opcoes.senhaInicial(), nome[0], nome[1],
                    emailReal != null ? emailReal : emailPadrao, idNumber));
            return new UsuarioMoodle(id, nome[0], nome[1], emailReal != null ? emailReal : emailPadrao);
        } catch (MoodleException e) {
            if (emailReal == null) {
                throw e;
            }
            // o e-mail real pode estar em uso por outra conta: cai para o e-mail padrão e segue
            log.warn("[PROCESSA] Moodle recusou o e-mail {} para {}; usando {}", emailReal, username, emailPadrao);
            long id = moodle.criarUsuario(new NovoUsuarioMoodle(username, opcoes.senhaInicial(), nome[0], nome[1],
                    emailPadrao, idNumber));
            return new UsuarioMoodle(id, nome[0], nome[1], emailPadrao);
        }
    }

    private UsuarioMoodle atualizarSeNecessario(UsuarioMoodle atual, Participante p, boolean professor, Execucao ex) {
        String[] nome = dividirNome(p.nome());
        boolean mudouNome = !nome[0].equals(atual.primeiroNome()) || !nome[1].equals(atual.sobrenome());
        String emailNovo = emailValido(p.email()) ? p.email().trim() : null;
        boolean mudouEmail = emailNovo != null && !emailNovo.equalsIgnoreCase(atual.email());
        if (!mudouNome && !mudouEmail) {
            return atual;
        }
        try {
            moodle.atualizarUsuario(atual.id(), nome[0], nome[1], mudouEmail ? emailNovo : null);
            ex.resumo.usuariosAtualizados++;
            return new UsuarioMoodle(atual.id(), nome[0], nome[1], mudouEmail ? emailNovo : atual.email());
        } catch (MoodleException e) {
            ex.erro((professor ? "Professor " : "Aluno ") + p.id() + " (atualização)", e);
            return atual;
        }
    }

    static String[] dividirNome(String nomeCompleto) {
        String nome = nomeCompleto == null ? "" : nomeCompleto.trim().replaceAll("\\s+", " ");
        if (nome.isEmpty()) {
            return new String[]{"(sem nome)", "-"};
        }
        int espaco = nome.indexOf(' ');
        return espaco < 0 ? new String[]{nome, "-"} : new String[]{nome.substring(0, espaco), nome.substring(espaco + 1)};
    }

    private static boolean emailValido(String email) {
        return email != null && EMAIL.matcher(email.trim()).matches();
    }

    /** Contadores mutáveis de uma aplicação. */
    private static final class Execucao {
        final Contadores resumo = new Contadores();
        final List<Mapeamento> mapeamentos = new ArrayList<>();
        final List<String> erros = new ArrayList<>();

        void erro(String contexto, RuntimeException e) {
            log.warn("[PROCESSA] {}: {}", contexto, e.getMessage());
            erros.add(contexto + ": " + e.getMessage());
        }

        ResultadoSincronizacao concluir(TurmaComando c, Long categoriaId, Long cursoId) {
            StatusSincronizacao status = erros.isEmpty() ? StatusSincronizacao.SUCESSO : StatusSincronizacao.PARCIAL;
            return montar(c, status, categoriaId, cursoId);
        }

        ResultadoSincronizacao falha(TurmaComando c) {
            return montar(c, StatusSincronizacao.FALHA, null, null);
        }

        private ResultadoSincronizacao montar(TurmaComando c, StatusSincronizacao status, Long categoriaId, Long cursoId) {
            return new ResultadoSincronizacao(c.turma().id(), c.versao(), status, categoriaId, cursoId,
                    resumo.paraResumo(), mapeamentos, List.copyOf(new LinkedHashSet<>(erros)), 1);
        }
    }

    private static final class Contadores {
        int cursosCriados, cursosAtualizados, cursosOcultados, usuariosCriados, usuariosAtualizados,
                inscricoesCriadas, inscricoesSuspensas;

        Resumo paraResumo() {
            return new Resumo(cursosCriados, cursosAtualizados, cursosOcultados, usuariosCriados, usuariosAtualizados,
                    inscricoesCriadas, inscricoesSuspensas);
        }
    }
}
