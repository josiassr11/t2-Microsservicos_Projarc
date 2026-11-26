package com.pucrs.publicadormoodle.domain.exception;

/** O broker não confirmou (ou devolveu) uma mensagem publicada: o processamento deve ser repetido (requeue). */
public class FalhaDePublicacaoException extends RuntimeException {

    public FalhaDePublicacaoException(String mensagem) {
        super(mensagem);
    }

    public FalhaDePublicacaoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
