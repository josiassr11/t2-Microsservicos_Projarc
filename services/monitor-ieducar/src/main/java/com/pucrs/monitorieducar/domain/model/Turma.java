package com.pucrs.monitorieducar.domain.model;

public record Turma(long id, String nome, int ano, boolean ativa, long escolaId, String escolaNome, Curso curso) {
}
