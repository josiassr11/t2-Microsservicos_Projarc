package com.pucrs.monitorieducar.domain.model;

import java.util.List;

/** Foto completa de uma turma no iEducar (turma + alunos + professores, inclusive inativos). */
public record TurmaSnapshot(Turma turma, List<Participante> alunos, List<Participante> professores) {
}
