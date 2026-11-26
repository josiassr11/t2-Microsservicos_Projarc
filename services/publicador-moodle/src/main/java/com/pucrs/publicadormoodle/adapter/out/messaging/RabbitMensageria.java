package com.pucrs.publicadormoodle.adapter.out.messaging;

import com.pucrs.publicadormoodle.application.ComandoRecebido;
import com.pucrs.publicadormoodle.config.JsonSupport;
import com.pucrs.publicadormoodle.config.Topologia;
import com.pucrs.publicadormoodle.domain.exception.FalhaDePublicacaoException;
import com.pucrs.publicadormoodle.domain.model.ResultadoSincronizacao;
import com.pucrs.publicadormoodle.domain.model.StatusSincronizacao;
import com.pucrs.publicadormoodle.domain.port.out.Destino;
import com.pucrs.publicadormoodle.domain.port.out.MensageriaPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publicações do serviço, sempre com publisher confirm (e mandatory: sem fila ligada = falha). */
@Component
public class RabbitMensageria implements MensageriaPort {

    private static final Logger log = LoggerFactory.getLogger(RabbitMensageria.class);
    static final String ORIGEM = "publicador-moodle";
    private static final long TIMEOUT_CONFIRM_S = 5;

    private final RabbitTemplate rabbit;

    public RabbitMensageria(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /**
     * SUCESSO/PARCIAL: id determinístico por (comando, status) — reemitir o mesmo resultado é idempotente para o
     * sincronizador. FALHA: id novo a cada vez, porque é um fato novo (um comando que falhou, foi reprocessado da DLQ e
     * agora teve sucesso NÃO pode ser descartado como "duplicata" do resultado FALHA anterior).
     */
    static UUID eventIdDoResultado(UUID commandId, StatusSincronizacao status) {
        if (status == StatusSincronizacao.FALHA) {
            return UUID.randomUUID();
        }
        return UUID.nameUUIDFromBytes(("res:" + commandId + ":" + status).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void publicarResultado(UUID commandId, String correlationId, ResultadoSincronizacao resultado) {
        UUID eventId = eventIdDoResultado(commandId, resultado.status());
        ObjectNode envelope = JsonSupport.MAPPER.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("tipo", "TurmaSincronizada");
        envelope.put("versao", 1);
        envelope.put("ocorridoEm", Instant.now().toString());
        envelope.put("correlationId", correlationId);
        envelope.put("origem", ORIGEM);
        envelope.set("dados", JsonSupport.MAPPER.valueToTree(resultado));
        byte[] corpo = serializar(envelope);

        Message msg = MessageBuilder.withBody(corpo)
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(eventId.toString())
                .setType("TurmaSincronizada.v1")
                .setCorrelationId(correlationId)
                .setHeader("x-origem", ORIGEM)
                .build();
        publicar(Topologia.EXCHANGE_RESULTADOS, Topologia.RK_RESULTADO, msg,
                "TurmaSincronizada.v1 eventId=" + eventId + " turmaId=" + resultado.turmaId() + " status=" + resultado.status());
    }

    @Override
    public void reenfileirar(ComandoRecebido c, Destino destino, int tentativa) {
        Message msg = MessageBuilder.withBody(c.corpoOriginal())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(c.commandId().toString())
                .setType("SincronizarTurma.v1")
                .setCorrelationId(c.correlationId())
                .setHeader(Topologia.HEADER_TENTATIVA, tentativa)
                .setHeader("x-origem", ORIGEM)
                .build();
        publicar("", Topologia.filaDe(destino), msg,
                "SincronizarTurma.v1 eventId=" + c.commandId() + " turmaId=" + c.turmaId() + " x-tentativa=" + tentativa);
    }

    @Override
    public void enviarIlegivelParaDlq(byte[] corpo, String messageId, String correlationId, int tentativa, String motivo) {
        MessageBuilderSupport<Message> b = MessageBuilder.withBody(corpo)
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader(Topologia.HEADER_TENTATIVA, tentativa)
                .setHeader("x-motivo", motivo)
                .setHeader("x-origem", ORIGEM);
        if (messageId != null) {
            b.setMessageId(messageId);
        }
        if (correlationId != null) {
            b.setCorrelationId(correlationId);
        }
        publicar("", Topologia.FILA_DLQ, b.build(), "mensagem ilegível messageId=" + messageId);
    }

    private void publicar(String exchange, String routingKey, Message msg, String descricao) {
        String messageId = msg.getMessageProperties().getMessageId();
        CorrelationData cd = new CorrelationData(messageId != null ? messageId : UUID.randomUUID().toString());
        long inicio = System.nanoTime();
        try {
            rabbit.send(exchange, routingKey, msg, cd);
            CorrelationData.Confirm confirm = cd.getFuture().get(TIMEOUT_CONFIRM_S, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new FalhaDePublicacaoException("broker recusou (nack) a mensagem: " + confirm.getReason());
            }
            if (cd.getReturned() != null) {
                throw new FalhaDePublicacaoException("mensagem sem destino (nenhuma fila ligada a " + exchange + " / " + routingKey + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDePublicacaoException("interrompido aguardando o confirm do broker", e);
        } catch (ExecutionException | TimeoutException | org.springframework.amqp.AmqpException e) {
            throw new FalhaDePublicacaoException("broker não confirmou a publicação (" + e.getMessage() + ")", e);
        }
        log.info("[PUBLICA] exchange={} rk={} {} -> confirmado pelo broker em {}ms",
                exchange.isEmpty() ? "(default)" : exchange, routingKey, descricao, (System.nanoTime() - inicio) / 1_000_000);
    }

    private static byte[] serializar(ObjectNode n) {
        try {
            return JsonSupport.MAPPER.writeValueAsBytes(n);
        } catch (JacksonException e) {
            throw new IllegalStateException("Não foi possível serializar a mensagem", e);
        }
    }
}
