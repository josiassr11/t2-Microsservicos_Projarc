package com.pucrs.sincronizador.domain.model;

public record TurmaInfo(long id, String nome, int ano, boolean ativa, long escolaId, String escolaNome,
                        long cursoId, String cursoNome) {
}
