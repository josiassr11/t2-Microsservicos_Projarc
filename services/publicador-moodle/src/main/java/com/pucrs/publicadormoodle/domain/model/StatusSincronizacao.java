package com.pucrs.publicadormoodle.domain.model;

public enum StatusSincronizacao {
    /** Tudo aplicado sem erros. */
    SUCESSO,
    /** Terminou, mas alguns itens falharam (ver {@code erros}). */
    PARCIAL,
    /** Não foi possível aplicar a turma (erro de negócio no curso ou tentativas esgotadas). */
    FALHA
}
