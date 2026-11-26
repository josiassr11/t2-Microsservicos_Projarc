package com.pucrs.publicadormoodle.application;

import com.pucrs.publicadormoodle.domain.model.Participante;
import com.pucrs.publicadormoodle.domain.model.TurmaComando;
import com.pucrs.publicadormoodle.domain.model.TurmaDados;

import java.util.List;

final class Fixtures {

    private Fixtures() {
    }

    static TurmaDados turma(long id, boolean ativa) {
        return new TurmaDados(id, "Turma A", 2026, ativa, 1, "Escola X", 3, "Engenharia de Software");
    }

    static TurmaComando comando(int versao, boolean turmaAtiva, List<Participante> alunos, List<Participante> professores) {
        return new TurmaComando(versao, "hash-" + versao, turma(10, turmaAtiva), alunos, professores);
    }

    static Participante aluno(long id, String nome, String email, boolean ativo) {
        return new Participante(id, nome, email, ativo);
    }
}
