package com.pucrs.monitorieducar.application;

import com.pucrs.monitorieducar.domain.model.Curso;
import com.pucrs.monitorieducar.domain.model.Participante;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.TurmaSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotHasherTest {

    static final Turma TURMA = new Turma(10, "1º Ano A", 2026, true, 1, "Escola X", new Curso(3, "Fundamental", true));
    static final Participante ANA = new Participante(7, "Ana Silva", "ana@x.com", true);
    static final Participante BIA = new Participante(8, "Bia Lima", null, true);
    static final Participante CARLOS = new Participante(2, "Carlos Souza", null, true);

    static TurmaSnapshot snap(Turma t, List<Participante> alunos, List<Participante> professores) {
        return new TurmaSnapshot(t, alunos, professores);
    }

    @Test
    void mesmoConteudoGeraMesmoHash_eTem64CaracteresHex() {
        String h = SnapshotHasher.hash(snap(TURMA, List.of(ANA, BIA), List.of(CARLOS)));
        assertThat(h).hasSize(64).matches("[0-9a-f]+");
        assertThat(SnapshotHasher.hash(snap(TURMA, List.of(ANA, BIA), List.of(CARLOS)))).isEqualTo(h);
    }

    @Test
    void ordemDaListagemNaoAlteraOHash() {
        assertThat(SnapshotHasher.hash(snap(TURMA, List.of(ANA, BIA), List.of(CARLOS))))
                .isEqualTo(SnapshotHasher.hash(snap(TURMA, List.of(BIA, ANA), List.of(CARLOS))));
    }

    @Test
    void nullEEmailVazioSaoTratadosIgual() {
        Participante comNull = new Participante(8, "Bia Lima", null, true);
        Participante comVazio = new Participante(8, "  Bia Lima ", "", true);
        assertThat(SnapshotHasher.hash(snap(TURMA, List.of(comNull), List.of())))
                .isEqualTo(SnapshotHasher.hash(snap(TURMA, List.of(comVazio), List.of())));
    }

    @Test
    void mudancaEmParticipanteMudaOHash() {
        String base = SnapshotHasher.hash(snap(TURMA, List.of(ANA), List.of(CARLOS)));
        assertThat(SnapshotHasher.hash(snap(TURMA, List.of(new Participante(7, "Ana Souza", "ana@x.com", true)), List.of(CARLOS))))
                .as("nome").isNotEqualTo(base);
        assertThat(SnapshotHasher.hash(snap(TURMA, List.of(new Participante(7, "Ana Silva", "ana@x.com", false)), List.of(CARLOS))))
                .as("enturmação cancelada").isNotEqualTo(base);
        assertThat(SnapshotHasher.hash(snap(TURMA, List.of(ANA, BIA), List.of(CARLOS)))).as("novo aluno").isNotEqualTo(base);
        assertThat(SnapshotHasher.hash(snap(TURMA, List.of(ANA), List.of()))).as("professor removido").isNotEqualTo(base);
    }

    @Test
    void mudancaNaTurmaMudaOHash() {
        String base = SnapshotHasher.hash(snap(TURMA, List.of(ANA), List.of()));
        Turma renomeada = new Turma(10, "1º Ano B", 2026, true, 1, "Escola X", new Curso(3, "Fundamental", true));
        Turma inativa = new Turma(10, "1º Ano A", 2026, false, 1, "Escola X", new Curso(3, "Fundamental", true));
        assertThat(SnapshotHasher.hash(snap(renomeada, List.of(ANA), List.of()))).isNotEqualTo(base);
        assertThat(SnapshotHasher.hash(snap(inativa, List.of(ANA), List.of()))).isNotEqualTo(base);
    }

    @Test
    void alunoEProfessorComMesmoIdNaoSeConfundem() {
        assertThat(SnapshotHasher.hash(snap(TURMA, List.of(ANA), List.of())))
                .isNotEqualTo(SnapshotHasher.hash(snap(TURMA, List.of(), List.of(ANA))));
    }
}
