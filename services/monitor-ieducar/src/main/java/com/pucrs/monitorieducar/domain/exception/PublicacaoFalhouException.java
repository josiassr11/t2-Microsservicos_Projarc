package com.pucrs.monitorieducar.domain.exception;

/** O broker não confirmou a mensagem (nack, timeout, conexão caída) ou ela não tinha para onde ir (unroutable). */
public class PublicacaoFalhouException extends RuntimeException {

    public PublicacaoFalhouException(String mensagem) {
        super(mensagem);
    }

    public PublicacaoFalhouException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
