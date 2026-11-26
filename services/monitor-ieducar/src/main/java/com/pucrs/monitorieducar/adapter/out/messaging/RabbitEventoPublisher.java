package com.pucrs.monitorieducar.adapter.out.messaging;

import com.pucrs.monitorieducar.config.MonitorProperties;
import com.pucrs.monitorieducar.config.RabbitConfig;
import com.pucrs.monitorieducar.domain.exception.PublicacaoFalhouException;
import com.pucrs.monitorieducar.domain.model.EventoTurmaAlterada;
import com.pucrs.monitorieducar.domain.port.out.PublicadorEventosPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica {@code TurmaAlterada.v1} no exchange {@code ieducar.eventos} e <b>espera o publisher confirm</b>.
 * Com {@code mandatory=true}, mensagem sem fila ligada volta ao publisher (return) e também é tratada como falha.
 */
@Component
public class RabbitEventoPublisher implements PublicadorEventosPort {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventoPublisher.class);

    private final RabbitTemplate template;
    private final MonitorProperties props;
    private final MeterRegistry meters;

    public RabbitEventoPublisher(RabbitTemplate template, MonitorProperties props, MeterRegistry meters) {
        this.template = template;
        this.props = props;
        this.meters = meters;
        // O retorno (unroutable) é tratado em publicar() via CorrelationData; o callback só evita o WARN padrão.
        template.setReturnsCallback(r -> log.debug("[PUBLICA] broker devolveu a mensagem: {} ({})", r.getReplyText(),
                r.getRoutingKey()));
    }

    @Override
    public void publicar(EventoTurmaAlterada evento) {
        String eventId = evento.eventId().toString();
        long turmaId = evento.snapshot().turma().id();
        Message mensagem = MessageBuilder
                .withBody(EnvelopeTurmaAlterada.paraJson(evento).getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setMessageId(eventId)
                .setType(EnvelopeTurmaAlterada.tipoAmqp())
                .setCorrelationId(evento.correlationId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("x-origem", EnvelopeTurmaAlterada.ORIGEM)
                .build();
        CorrelationData correlacao = new CorrelationData(eventId);
        long inicio = System.nanoTime();
        try {
            template.send(RabbitConfig.EXCHANGE_EVENTOS, RabbitConfig.ROUTING_KEY_TURMA_ALTERADA, mensagem, correlacao);
            CorrelationData.Confirm confirm = correlacao.getFuture()
                    .get(props.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new PublicacaoFalhouException("broker recusou a mensagem (nack: " + confirm.getReason() + ")");
            }
            if (correlacao.getReturned() != null) {
                throw new PublicacaoFalhouException("mensagem sem destino (unroutable): nenhuma fila ligada a "
                        + RabbitConfig.EXCHANGE_EVENTOS + " com a chave " + RabbitConfig.ROUTING_KEY_TURMA_ALTERADA
                        + " (o sincronizador já criou a sua fila?)");
            }
        } catch (AmqpException e) {
            throw new PublicacaoFalhouException("RabbitMQ indisponível: " + e.getMessage(), e);
        } catch (TimeoutException e) {
            throw new PublicacaoFalhouException("broker não confirmou em " + props.confirmTimeout().toMillis() + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublicacaoFalhouException("publicação interrompida", e);
        } catch (ExecutionException e) {
            throw new PublicacaoFalhouException("falha ao aguardar confirmação: " + e.getCause(), e);
        }
        log.info("[PUBLICA] exchange={} rk={} tipo={} eventId={} turmaId={} mudanca={} -> confirmado pelo broker em {}ms",
                RabbitConfig.EXCHANGE_EVENTOS, RabbitConfig.ROUTING_KEY_TURMA_ALTERADA,
                EnvelopeTurmaAlterada.tipoAmqp(), eventId, turmaId, evento.mudanca(), (System.nanoTime() - inicio) / 1_000_000);
        meters.counter("monitor.eventos.publicados", "tipo", EnvelopeTurmaAlterada.tipoAmqp()).increment();
    }
}
