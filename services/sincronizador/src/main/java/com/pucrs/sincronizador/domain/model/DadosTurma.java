package com.pucrs.sincronizador.domain.model;

import java.util.List;

/** Foto completa da turma (evento completo): tudo o que o publicador precisa, sem chamar o iEducar. */
public record DadosTurma(String hash, TurmaInfo turma, List<Participante> alunos, List<Participante> professores) {

    public DadosTurma {
        alunos = alunos == null ? List.of() : List.copyOf(alunos);
        professores = professores == null ? List.of() : List.copyOf(professores);
    }
}
