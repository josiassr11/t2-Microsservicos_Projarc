package com.pucrs.monitorieducar.domain.exception;

/** Erro ao falar com o iEducar. */
public class SistemaExternoException extends RuntimeException {

    private final String sistema;

    public SistemaExternoException(String sistema, String mensagem) {
        super(mensagem);
        this.sistema = sistema;
    }

    public SistemaExternoException(String sistema, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.sistema = sistema;
    }

    public String getSistema() {
        return sistema;
    }
}
