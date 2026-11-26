package com.pucrs.sincronizador.domain.model;

/** Aluno ou professor da turma (o papel é dado pela lista em que aparece). */
public record Participante(long id, String nome, String email, boolean ativo) {
}
