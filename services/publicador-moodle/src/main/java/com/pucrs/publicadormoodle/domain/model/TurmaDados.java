package com.pucrs.publicadormoodle.domain.model;

public record TurmaDados(long id, String nome, int ano, boolean ativa, long escolaId, String escolaNome,
                         long cursoId, String cursoNome) {

    /** Nome do curso no Moodle (mesma regra do T1). */
    public String nomeNoMoodle() {
        return cursoNome + " — " + nome;
    }
}
