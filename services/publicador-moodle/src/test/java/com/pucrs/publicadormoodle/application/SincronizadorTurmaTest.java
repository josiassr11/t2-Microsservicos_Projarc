package com.pucrs.publicadormoodle.application;

import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import com.pucrs.publicadormoodle.domain.model.Mapeamento;
import com.pucrs.publicadormoodle.domain.model.Mapeamento.Tipo;
import com.pucrs.publicadormoodle.domain.model.Participante;
import com.pucrs.publicadormoodle.domain.model.ResultadoSincronizacao;
import com.pucrs.publicadormoodle.domain.model.StatusSincronizacao;
import com.pucrs.publicadormoodle.domain.model.TurmaComando;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.pucrs.publicadormoodle.application.Fixtures.aluno;
import static com.pucrs.publicadormoodle.application.Fixtures.comando;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SincronizadorTurmaTest {

    FakeMoodle moodle;
    SincronizadorTurma servico;

    @BeforeEach
    void preparar() {
        moodle = new FakeMoodle();
        servico = new SincronizadorTurma(moodle, new OpcoesMoodle(5, 3, "Mudar@2026!", "exemplo.edu.br"));
    }

    TurmaComando turmaComGente() {
        return comando(1, true,
                List.of(aluno(7, "Ana Silva", "ana@x.com", true), aluno(8, "Bruno Lima", null, true)),
                List.of(new Participante(2, "Carlos Souza", null, true)));
    }

    @Test
    void criaCategoriaCursoUsuariosEInscricoes() {
        ResultadoSincronizacao r = servico.aplicar(turmaComGente());

        assertThat(r.status()).isEqualTo(StatusSincronizacao.SUCESSO);
        assertThat(moodle.categorias).containsKey("IEDUCAR-CURSO-3");
        assertThat(moodle.cursos).containsKey("IEDUCAR-TURMA-10");
        assertThat(moodle.usuarios).containsOnlyKeys("IEDUCAR-ALUNO-7", "IEDUCAR-ALUNO-8", "IEDUCAR-SERVIDOR-2");
        assertThat(r.resumo().cursosCriados()).isEqualTo(1);
        assertThat(r.resumo().usuariosCriados()).isEqualTo(3);
        assertThat(r.resumo().inscricoesCriadas()).isEqualTo(3);
        long curso = moodle.cursos.get("IEDUCAR-TURMA-10").id();
        assertThat(r.cursoMoodleId()).isEqualTo(curso);
        assertThat(r.categoriaMoodleId()).isEqualTo(moodle.categorias.get("IEDUCAR-CURSO-3"));
        // papéis: aluno=5, professor=3
        long prof = moodle.usuarios.get("IEDUCAR-SERVIDOR-2").id();
        long ana = moodle.usuarios.get("IEDUCAR-ALUNO-7").id();
        assertThat(moodle.papeis.get(curso + ":" + prof)).isEqualTo(3L);
        assertThat(moodle.papeis.get(curso + ":" + ana)).isEqualTo(5L);
        // e-mail real usado quando válido; padrão quando ausente
        assertThat(moodle.usuarios.get("IEDUCAR-ALUNO-7").email()).isEqualTo("ana@x.com");
        assertThat(moodle.usuarios.get("IEDUCAR-ALUNO-8").email()).isEqualTo("aluno8@exemplo.edu.br");
    }

    @Test
    void devolveOMapeamentoIeducarParaMoodle() {
        ResultadoSincronizacao r = servico.aplicar(turmaComGente());

        assertThat(r.mapeamentos()).extracting(Mapeamento::tipo)
                .containsExactlyInAnyOrder(Tipo.CATEGORIA, Tipo.CURSO, Tipo.ALUNO, Tipo.ALUNO, Tipo.PROFESSOR);
        assertThat(r.mapeamentos()).contains(
                new Mapeamento(Tipo.CATEGORIA, 3, moodle.categorias.get("IEDUCAR-CURSO-3"), "IEDUCAR-CURSO-3"),
                new Mapeamento(Tipo.CURSO, 10, moodle.cursos.get("IEDUCAR-TURMA-10").id(), "IEDUCAR-TURMA-10"),
                new Mapeamento(Tipo.PROFESSOR, 2, moodle.usuarios.get("IEDUCAR-SERVIDOR-2").id(), "IEDUCAR-SERVIDOR-2"));
    }

    @Test
    void reexecutarOMesmoComandoNaoDuplicaNada() {
        servico.aplicar(turmaComGente());
        int criacoes = moodle.chamadasCriar;

        ResultadoSincronizacao segunda = servico.aplicar(turmaComGente());

        assertThat(moodle.chamadasCriar).isEqualTo(criacoes);
        assertThat(segunda.status()).isEqualTo(StatusSincronizacao.SUCESSO);
        assertThat(segunda.resumo().cursosCriados()).isZero();
        assertThat(segunda.resumo().usuariosCriados()).isZero();
        assertThat(segunda.resumo().inscricoesCriadas()).isZero();
        assertThat(segunda.mapeamentos()).hasSize(5);
    }

    @Test
    void renomearATurmaAtualizaOCursoNoMoodle() {
        servico.aplicar(turmaComGente());
        TurmaComando renomeada = new TurmaComando(2, "h", new com.pucrs.publicadormoodle.domain.model.TurmaDados(10, "Turma Z", 2026,
                true, 1, "Escola X", 3, "Engenharia de Software"), turmaComGente().alunos(), turmaComGente().professores());

        ResultadoSincronizacao r = servico.aplicar(renomeada);

        assertThat(r.resumo().cursosAtualizados()).isEqualTo(1);
        assertThat(moodle.cursos.get("IEDUCAR-TURMA-10").nomeCompleto()).isEqualTo("Engenharia de Software — Turma Z");
    }

    @Test
    void turmaInativaSemCursoNoMoodleNaoFazNada() {
        ResultadoSincronizacao r = servico.aplicar(comando(1, false, List.of(aluno(7, "Ana", null, true)), List.of()));

        assertThat(r.status()).isEqualTo(StatusSincronizacao.SUCESSO);
        assertThat(r.mapeamentos()).isEmpty();
        assertThat(r.cursoMoodleId()).isNull();
        assertThat(moodle.chamadasCriar).isZero();
    }

    @Test
    void turmaInativadaOcultaOCursoSemApagar() {
        servico.aplicar(turmaComGente());

        ResultadoSincronizacao r = servico.aplicar(comando(2, false, turmaComGente().alunos(), turmaComGente().professores()));

        assertThat(r.resumo().cursosOcultados()).isEqualTo(1);
        assertThat(moodle.cursos.get("IEDUCAR-TURMA-10").visivel()).isFalse();
        assertThat(moodle.cursos).hasSize(1);
        assertThat(r.mapeamentos()).extracting(Mapeamento::tipo).contains(Tipo.CURSO);
    }

    @Test
    void matriculaCanceladaSuspendeAInscricaoSemApagarOUsuario() {
        servico.aplicar(turmaComGente());
        long curso = moodle.cursos.get("IEDUCAR-TURMA-10").id();
        long ana = moodle.usuarios.get("IEDUCAR-ALUNO-7").id();

        ResultadoSincronizacao r = servico.aplicar(comando(2, true,
                List.of(aluno(7, "Ana Silva", "ana@x.com", false), aluno(8, "Bruno Lima", null, true)),
                List.of(new Participante(2, "Carlos Souza", null, true))));

        assertThat(r.resumo().inscricoesSuspensas()).isEqualTo(1);
        assertThat(moodle.inscricoes.get(curso).get(ana)).isTrue();
        assertThat(moodle.usuarios).containsKey("IEDUCAR-ALUNO-7");
    }

    @Test
    void alunoInativoQueNuncaExistiuNoMoodleNaoEhCriado() {
        ResultadoSincronizacao r = servico.aplicar(comando(1, true, List.of(aluno(9, "Fantasma", null, false)), List.of()));

        assertThat(moodle.usuarios).doesNotContainKey("IEDUCAR-ALUNO-9");
        assertThat(r.status()).isEqualTo(StatusSincronizacao.SUCESSO);
    }

    @Test
    void emailRealRecusadoCaiParaOEmailPadrao() {
        moodle.emailsUsados.add("ana@x.com");

        ResultadoSincronizacao r = servico.aplicar(comando(1, true, List.of(aluno(7, "Ana Silva", "ana@x.com", true)), List.of()));

        assertThat(r.status()).isEqualTo(StatusSincronizacao.SUCESSO);
        assertThat(moodle.usuarios.get("IEDUCAR-ALUNO-7").email()).isEqualTo("aluno7@exemplo.edu.br");
    }

    @Test
    void falhaDeNegocioEmUmItemViraParcialEOsDemaisSeguem() {
        moodle.emailsUsados.add("aluno8@exemplo.edu.br"); // o e-mail padrão do aluno 8 já está em uso e ele não tem e-mail real

        ResultadoSincronizacao r = servico.aplicar(turmaComGente());

        assertThat(r.status()).isEqualTo(StatusSincronizacao.PARCIAL);
        assertThat(r.erros()).singleElement().asString().contains("Aluno 8");
        assertThat(moodle.usuarios).containsKeys("IEDUCAR-ALUNO-7", "IEDUCAR-SERVIDOR-2").doesNotContainKey("IEDUCAR-ALUNO-8");
        assertThat(r.resumo().inscricoesCriadas()).isEqualTo(2);
    }

    @Test
    void falhaDeNegocioNoCursoViraFalha() {
        // curso "fantasma": buscarCurso não o enxerga, mas criarCurso encontra o idnumber já usado
        FakeMoodle teimoso = new FakeMoodle() {
            @Override public java.util.Optional<com.pucrs.publicadormoodle.domain.model.CursoMoodle> buscarCurso(String idNumber) { return java.util.Optional.empty(); }
        };
        teimoso.cursos.put("IEDUCAR-TURMA-10", new com.pucrs.publicadormoodle.domain.model.CursoMoodle(1, "x", true, 1));
        SincronizadorTurma s = new SincronizadorTurma(teimoso, new OpcoesMoodle(5, 3, "x", "exemplo.edu.br"));

        ResultadoSincronizacao r = s.aplicar(turmaComGente());

        assertThat(r.status()).isEqualTo(StatusSincronizacao.FALHA);
        assertThat(r.erros()).isNotEmpty();
        assertThat(r.mapeamentos()).isEmpty();
    }

    @Test
    void moodleIndisponivelPropagaParaPoliticaDeRetry() {
        moodle.fora = true;

        assertThatThrownBy(() -> servico.aplicar(turmaComGente())).isInstanceOf(SistemaIndisponivelException.class);
    }

    @Test
    void dividirNome() {
        assertThat(SincronizadorTurma.dividirNome("  Maria   da Silva ")).containsExactly("Maria", "da Silva");
        assertThat(SincronizadorTurma.dividirNome("Cher")).containsExactly("Cher", "-");
        assertThat(SincronizadorTurma.dividirNome(null)).containsExactly("(sem nome)", "-");
    }
}
