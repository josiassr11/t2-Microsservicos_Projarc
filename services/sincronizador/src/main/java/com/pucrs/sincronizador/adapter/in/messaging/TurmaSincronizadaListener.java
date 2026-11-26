package com.pucrs.sincronizador.adapter.in.messaging;

import com.pucrs.sincronizador.config.RabbitConfig;
import com.pucrs.sincronizador.domain.model.ResultadoProcessamento;
import com.pucrs.sincronizador.domain.model.ResultadoSincronizacao;
import com.pucrs.sincronizador.domain.port.in.ProcessarMensagensUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Entrada de mensagens {@code TurmaSincronizada.v1} (resultado publicado pelo publicador-moodle). */
@Component
public class TurmaSincronizadaListener {

    private static final Logger log = LoggerFactory.getLogger(TurmaSincronizadaListener.class);

    private final EnvelopeParser parser;
    private final ProcessarMensagensUseCase useCase;
    private final PoliticaDeTentativas tentativas;
    private final MeterRegistry metricas;

    public TurmaSincronizadaListener(EnvelopeParser parser, ProcessarMensagensUseCase useCase,
                                     PoliticaDeTentativas tentativas, MeterRegistry metricas) {
        this.parser = parser;
        this.useCase = useCase;
        this.tentativas = tentativas;
        this.metricas = metricas;
    }

    @RabbitListener(queues = RabbitConfig.FILA_TURMA_SINCRONIZADA)
    public void receber(Message message) {
        String corrDaMensagem = message.getMessageProperties().getCorrelationId();
        if (corrDaMensagem != null) {
            MDC.put("correlationId", corrDaMensagem);
        }
        try {
            ResultadoSincronizacao resultado;
            try {
                resultado = parser.turmaSincronizada(message);
            } catch (MensagemInvalidaException e) {
                log.error("[DLQ] mensagem ilegível na fila {} (messageId={}): {} -> rejeitada sem requeue, vai para a DLQ",
                        RabbitConfig.FILA_TURMA_SINCRONIZADA, message.getMessageProperties().getMessageId(), e.getMessage());
                contar("invalida");
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            }
            MDC.put("correlationId", resultado.correlationId());
            log.info("[RECEBE] fila={} tipo=TurmaSincronizada.v1 eventId={} turmaId={} versao={} status={}",
                    RabbitConfig.FILA_TURMA_SINCRONIZADA, resultado.eventId(), resultado.turmaId(), resultado.versao(),
                    resultado.status());
            try {
                ResultadoProcessamento r = tentativas.executar("resultado da turma " + resultado.turmaId() + " evento "
                        + resultado.eventId(), RabbitConfig.FILA_TURMA_SINCRONIZADA, () -> useCase.processarResultado(resultado));
                contar(r.name().toLowerCase());
            } catch (AmqpRejectAndDontRequeueException e) {
                contar("dlq");
                throw e;
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void contar(String resultado) {
        metricas.counter("integracao.mensagens.consumidas", "tipo", "TurmaSincronizada", "resultado", resultado).increment();
    }
}
