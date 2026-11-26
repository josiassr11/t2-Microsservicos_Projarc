package com.pucrs.publicadormoodle.domain.port.out;

import com.pucrs.publicadormoodle.application.ComandoRecebido;
import com.pucrs.publicadormoodle.domain.model.ResultadoSincronizacao;

import java.util.UUID;

/** Saída de mensagens (RabbitMQ). Toda publicação espera o publisher confirm; falha => FalhaDePublicacaoException. */
public interface MensageriaPort {

    /** Publica {@code TurmaSincronizada.v1} (eventId determinístico a partir do commandId). */
    void publicarResultado(UUID commandId, String correlationId, ResultadoSincronizacao resultado);

    /** Reenfileira o comando original (mesmo corpo) em uma fila de retry ou na DLQ, com o header x-tentativa. */
    void reenfileirar(ComandoRecebido comando, Destino destino, int tentativa);

    /** Mensagem ilegível: vai direto para a DLQ (sem tentar de novo), guardando o motivo. */
    void enviarIlegivelParaDlq(byte[] corpo, String messageId, String correlationId, int tentativa, String motivo);
}
