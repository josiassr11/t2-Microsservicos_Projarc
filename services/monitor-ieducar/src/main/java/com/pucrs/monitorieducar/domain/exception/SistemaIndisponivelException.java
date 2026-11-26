package com.pucrs.monitorieducar.domain.exception;

/**
 * O iEducar não respondeu (rede, timeout, 5xx, credencial recusada, circuito aberto). É a única exceção que o
 * Retry repete e que o Circuit Breaker conta como falha.
 */
public class SistemaIndisponivelException extends SistemaExternoException {

    public SistemaIndisponivelException(String sistema, String mensagem, Throwable causa) {
        super(sistema, sistema + " indisponível: " + mensagem, causa);
    }

    public SistemaIndisponivelException(String sistema, String mensagem) {
        super(sistema, sistema + " indisponível: " + mensagem);
    }
}
