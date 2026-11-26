package com.pucrs.monitorieducar.domain.exception;

public class VarreduraEmAndamentoException extends RuntimeException {

    public VarreduraEmAndamentoException() {
        super("Já existe uma varredura em andamento.");
    }
}
