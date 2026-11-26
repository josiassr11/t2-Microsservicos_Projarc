package com.pucrs.publicadormoodle.domain.model;

import java.util.List;

/** Conteúdo do comando {@code SincronizarTurma.v1}. */
public record TurmaComando(int versao, String hash, TurmaDados turma, List<Participante> alunos,
                           List<Participante> professores) {

    public TurmaComando {
        alunos = alunos == null ? List.of() : List.copyOf(alunos);
        professores = professores == null ? List.of() : List.copyOf(professores);
    }
}
