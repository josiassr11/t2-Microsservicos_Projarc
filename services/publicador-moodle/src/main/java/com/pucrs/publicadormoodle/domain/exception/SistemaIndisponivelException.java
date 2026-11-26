package com.pucrs.publicadormoodle.domain.exception;

/**
 * O Moodle não respondeu (rede, timeout, 5xx, circuito aberto, token recusado). É uma falha <b>transitória</b>:
 * o comando é reenfileirado pela política de retry por fila (5s, 30s) e, esgotadas as tentativas, vai para a DLQ.
 */
public class SistemaIndisponivelException extends SistemaExternoException {

    public SistemaIndisponivelException(String sistema, String mensagem, Throwable causa) {
        super(sistema, sistema + " indisponível: " + mensagem, causa);
    }

    public SistemaIndisponivelException(String sistema, String mensagem) {
        super(sistema, sistema + " indisponível: " + mensagem);
    }
}
