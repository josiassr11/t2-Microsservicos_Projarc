package com.pucrs.publicadormoodle.domain.model;

/** Aluno ou professor da turma, como veio no comando (ativo=false => inscrição suspensa). */
public record Participante(long id, String nome, String email, boolean ativo) {
}
