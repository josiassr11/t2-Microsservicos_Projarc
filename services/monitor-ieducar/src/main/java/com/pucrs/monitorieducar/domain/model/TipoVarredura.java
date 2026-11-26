package com.pucrs.monitorieducar.domain.model;

public enum TipoVarredura {
    /** Só publica as turmas novas ou com hash diferente do snapshot. */
    INCREMENTAL,
    /** Republica todas as turmas (mudanca=REPUBLICADA), ignorando o snapshot. */
    COMPLETA
}
