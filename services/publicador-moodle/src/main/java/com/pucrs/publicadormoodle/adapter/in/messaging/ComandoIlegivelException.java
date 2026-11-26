package com.pucrs.publicadormoodle.adapter.in.messaging;

/** A mensagem recebida não é um {@code SincronizarTurma.v1} válido (poison message). */
public class ComandoIlegivelException extends RuntimeException {

    public ComandoIlegivelException(String motivo) {
        super(motivo);
    }

    public ComandoIlegivelException(String motivo, Throwable causa) {
        super(motivo, causa);
    }
}
