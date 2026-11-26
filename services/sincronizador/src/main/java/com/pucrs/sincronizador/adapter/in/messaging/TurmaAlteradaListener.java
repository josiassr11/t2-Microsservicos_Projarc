package com.pucrs.sincronizador.adapter.in.messaging;

import com.pucrs.sincronizador.config.RabbitConfig;
import com.pucrs.sincronizador.domain.model.EventoTurmaAlterada;
import com.pucrs.sincronizador.domain.model.ResultadoProcessamento;
import com.pucrs.sincronizador.domain.port.in.ProcessarMensagensUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Entrada de mensagens {@code TurmaAlterada.v1} (publicadas pelo monitor-ieducar). */
@Component
public class TurmaAlteradaListener {

    private static final Logger log = LoggerFactory.getLogger(TurmaAlteradaListener.class);

    private final EnvelopeParser parser;
    private final ProcessarMensagensUseCase useCase;
    private final PoliticaDeTentativas tentativas;
    private final MeterRegistry metricas;

    public TurmaAlteradaListener(EnvelopeParser parser, ProcessarMensagensUseCase useCase,
                                 PoliticaDeTentativas tentativas, MeterRegistry metricas) {
        this.parser = parser;
        this.useCase = useCase;
        this.tentativas = tentativas;
        this.metricas = metricas;
    }

    @RabbitListener(queues = RabbitConfig.FILA_TURMA_ALTERADA)
    public void receber(Message message) {
        String corrDaMensagem = message.getMessageProperties().getCorrelationId();
        if (corrDaMensagem != null) {
            MDC.put("correlationId", corrDaMensagem);
        }
        try {
            EventoTurmaAlterada evento;
            try {
                evento = parser.turmaAlterada(message);
            } catch (MensagemInvalidaException e) {
                log.error("[DLQ] mensagem ilegível na fila {} (messageId={}): {} -> rejeitada sem requeue, vai para a DLQ",
                        RabbitConfig.FILA_TURMA_ALTERADA, message.getMessageProperties().getMessageId(), e.getMessage());
                contar("invalida");
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            }
            MDC.put("correlationId", evento.correlationId());
            log.info("[RECEBE] fila={} tipo=TurmaAlterada.v1 eventId={} turmaId={} mudanca={} alunos={} professores={}",
                    RabbitConfig.FILA_TURMA_ALTERADA, evento.eventId(), evento.dados().turma().id(), evento.mudanca(),
                    evento.dados().alunos().size(), evento.dados().professores().size());
            try {
                ResultadoProcessamento r = tentativas.executar("turma " + evento.dados().turma().id() + " evento "
                        + evento.eventId(), RabbitConfig.FILA_TURMA_ALTERADA, () -> useCase.processarTurmaAlterada(evento));
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
        metricas.counter("integracao.mensagens.consumidas", "tipo", "TurmaAlterada", "resultado", resultado).increment();
    }
}
