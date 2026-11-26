package com.pucrs.sincronizador.domain.exception;

/** O broker não confirmou (ou não conseguiu rotear) a mensagem. */
public class PublicacaoException extends RuntimeException {

    public PublicacaoException(String mensagem) {
        super(mensagem);
    }

    public PublicacaoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
