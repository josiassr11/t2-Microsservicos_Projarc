package com.pucrs.sincronizador.adapter.in.messaging;

/** Mensagem ilegível, de tipo/versão desconhecidos ou sem campos essenciais: não adianta tentar de novo (vai para a DLQ). */
public class MensagemInvalidaException extends RuntimeException {

    public MensagemInvalidaException(String mensagem) {
        super(mensagem);
    }

    public MensagemInvalidaException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
