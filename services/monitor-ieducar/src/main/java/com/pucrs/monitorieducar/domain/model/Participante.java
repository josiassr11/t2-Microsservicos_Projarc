package com.pucrs.monitorieducar.domain.model;

/** Aluno ou professor de uma turma, como será enviado no evento. {@code ativo=false} = enturmação cancelada. */
public record Participante(long id, String nome, String email, boolean ativo) {
}
