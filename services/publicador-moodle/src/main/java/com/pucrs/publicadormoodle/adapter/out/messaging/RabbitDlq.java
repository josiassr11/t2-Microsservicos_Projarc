package com.pucrs.publicadormoodle.adapter.out.messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import com.pucrs.publicadormoodle.config.Topologia;
import com.pucrs.publicadormoodle.domain.port.out.DlqPort;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Operações sobre a DLQ: contar e devolver as mensagens à fila principal (uma a uma, com confirm). */
@Component
public class RabbitDlq implements DlqPort {

    private final AmqpAdmin admin;
    private final RabbitTemplate rabbit;

    public RabbitDlq(AmqpAdmin admin, RabbitTemplate rabbit) {
        this.admin = admin;
        this.rabbit = rabbit;
    }

    @Override
    public int quantidade() {
        Properties p = admin.getQueueProperties(Topologia.FILA_DLQ);
        Object n = p == null ? null : p.get("QUEUE_MESSAGE_COUNT");
        return n instanceof Number num ? num.intValue() : 0;
    }

    @Override
    public int reprocessar() {
        Integer movidas = rabbit.execute(channel -> {
            channel.confirmSelect();
            int n = 0;
            while (true) {
                GetResponse r = channel.basicGet(Topologia.FILA_DLQ, false);
                if (r == null) {
                    return n;
                }
                Map<String, Object> headers = new HashMap<>();
                if (r.getProps().getHeaders() != null) {
                    headers.putAll(r.getProps().getHeaders());
                }
                headers.remove("x-motivo");
                headers.put(Topologia.HEADER_TENTATIVA, 1);
                AMQP.BasicProperties props = r.getProps().builder().headers(headers).build();
                channel.basicPublish(Topologia.EXCHANGE_COMANDOS, Topologia.RK_COMANDO, true, props, r.getBody());
                channel.waitForConfirmsOrDie(5_000);
                channel.basicAck(r.getEnvelope().getDeliveryTag(), false);
                n++;
            }
        });
        return movidas == null ? 0 : movidas;
    }
}
