package com.pucrs.sincronizador.adapter.in.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Retry do consumidor: tenta até {@code max} vezes com espera fixa entre elas; se esgotar, rejeita sem recolocar na
 * fila, e o broker manda a mensagem para a DLQ (dead-letter exchange).
 */
public class PoliticaDeTentativas {

    private static final Logger log = LoggerFactory.getLogger(PoliticaDeTentativas.class);

    private final int max;
    private final Duration espera;

    public PoliticaDeTentativas(int max, Duration espera) {
        this.max = Math.max(1, max);
        this.espera = espera;
    }

    public <T> T executar(String descricao, String fila, Supplier<T> acao) {
        RuntimeException ultima = null;
        for (int tentativa = 1; tentativa <= max; tentativa++) {
            try {
                return acao.get();
            } catch (RuntimeException e) {
                ultima = e;
                if (tentativa < max) {
                    log.warn("[RETRY] {} falhou (tentativa {}/{}): {}. Nova tentativa em {} ms", descricao, tentativa,
                            max, e.getMessage(), espera.toMillis());
                    dormir();
                } else {
                    log.warn("[RETRY] {} falhou (tentativa {}/{}): {}", descricao, tentativa, max, e.getMessage());
                }
            }
        }
        log.error("[DLQ] {} esgotou {} tentativas: mensagem rejeitada sem requeue -> vai para a DLQ de '{}'",
                descricao, max, fila);
        throw new AmqpRejectAndDontRequeueException("esgotou " + max + " tentativas: " + ultima.getMessage(), ultima);
    }

    private void dormir() {
        try {
            Thread.sleep(espera.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
