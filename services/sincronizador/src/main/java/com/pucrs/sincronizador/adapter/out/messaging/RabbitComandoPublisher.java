package com.pucrs.sincronizador.adapter.out.messaging;

import com.pucrs.sincronizador.config.RabbitConfig;
import com.pucrs.sincronizador.domain.exception.PublicacaoException;
import com.pucrs.sincronizador.domain.model.ComandoSincronizar;
import com.pucrs.sincronizador.domain.port.out.ComandoPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publica {@code SincronizarTurma.v1} e só considera publicado depois do confirm do broker (e sem "unroutable"). */
@Component
public class RabbitComandoPublisher implements ComandoPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitComandoPublisher.class);
    private static final String TIPO = "SincronizarTurma";

    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final MeterRegistry metricas;
    private final Duration timeoutConfirm;

    public RabbitComandoPublisher(RabbitTemplate rabbit, JsonMapper json, MeterRegistry metricas,
                                  @org.springframework.beans.factory.annotation.Value("${sincronizador.publicacao.timeout-confirm:PT5S}") Duration timeoutConfirm) {
        this.rabbit = rabbit;
        this.json = json;
        this.metricas = metricas;
        this.timeoutConfirm = timeoutConfirm;
        this.rabbit.setMandatory(true);
        this.rabbit.setReturnsCallback(r -> log.warn("[PUBLICA] mensagem DEVOLVIDA pelo broker (sem fila ligada): exchange={} rk={} motivo={}",
                r.getExchange(), r.getRoutingKey(), r.getReplyText()));
    }

    @Override
    public void publicar(ComandoSincronizar c) {
        Message mensagem = montar(c);
        CorrelationData correlacao = new CorrelationData(c.commandId().toString());
        long inicio = System.nanoTime();
        try {
            rabbit.send(RabbitConfig.EXCHANGE_COMANDOS, RabbitConfig.RK_SINCRONIZAR_TURMA, mensagem, correlacao);
            CorrelationData.Confirm confirm = correlacao.getFuture().get(timeoutConfirm.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new PublicacaoException("broker NÃO confirmou (nack): " + confirm.getReason());
            }
            if (correlacao.getReturned() != null) {
                throw new PublicacaoException("broker devolveu a mensagem: nenhuma fila ligada a "
                        + RabbitConfig.EXCHANGE_COMANDOS + "/" + RabbitConfig.RK_SINCRONIZAR_TURMA);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublicacaoException("publicação interrompida", e);
        } catch (TimeoutException e) {
            throw new PublicacaoException("sem confirmação do broker em " + timeoutConfirm.toMillis() + " ms", e);
        } catch (ExecutionException | RuntimeException e) {
            if (e instanceof PublicacaoException pe) {
                throw pe;
            }
            throw new PublicacaoException("falha ao publicar no broker: " + e.getMessage(), e);
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        metricas.counter("integracao.comandos.publicados", "tipo", "SincronizarTurma.v1").increment();
        log.info("[PUBLICA] exchange={} rk={} tipo={}.v1 eventId={} turmaId={} versao={} -> confirmado pelo broker em {} ms",
                RabbitConfig.EXCHANGE_COMANDOS, RabbitConfig.RK_SINCRONIZAR_TURMA, TIPO, c.commandId(),
                c.dados().turma().id(), c.versao(), ms);
    }

    Message montar(ComandoSincronizar c) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventId", c.commandId().toString());
        envelope.put("tipo", TIPO);
        envelope.put("versao", 1);
        envelope.put("ocorridoEm", Instant.now().toString());
        envelope.put("correlationId", c.correlationId());
        envelope.put("origem", "sincronizador");
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("versao", c.versao());
        dados.put("hash", c.dados().hash());
        dados.put("turma", c.dados().turma());
        dados.put("alunos", c.dados().alunos());
        dados.put("professores", c.dados().professores());
        envelope.set("dados", json.valueToTree(dados));
        return MessageBuilder.withBody(json.writeValueAsBytes(envelope))
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setMessageId(c.commandId().toString())
                .setType(TIPO + ".v1")
                .setCorrelationId(c.correlationId())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("x-origem", "sincronizador")
                .build();
    }
}
