package com.pucrs.sincronizador.config;

import com.pucrs.sincronizador.adapter.in.messaging.PoliticaDeTentativas;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Topologia do RabbitMQ que pertence ao sincronizador (exchanges usados + as filas que ele consome + DLQs). */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE_EVENTOS_IEDUCAR = "ieducar.eventos";
    public static final String EXCHANGE_COMANDOS = "sincronizacao.comandos";
    public static final String EXCHANGE_EVENTOS_MOODLE = "moodle.eventos";
    public static final String EXCHANGE_DLX = "sincronizador.dlx";

    public static final String RK_TURMA_ALTERADA = "turma.alterada";
    public static final String RK_SINCRONIZAR_TURMA = "sincronizar.turma";
    public static final String RK_TURMA_SINCRONIZADA = "turma.sincronizada";

    public static final String FILA_TURMA_ALTERADA = "sincronizador.turma-alterada";
    public static final String FILA_TURMA_SINCRONIZADA = "sincronizador.turma-sincronizada";

    @Bean
    Declarables topologia() {
        TopicExchange eventosIeducar = new TopicExchange(EXCHANGE_EVENTOS_IEDUCAR, true, false);
        DirectExchange comandos = new DirectExchange(EXCHANGE_COMANDOS, true, false);
        TopicExchange eventosMoodle = new TopicExchange(EXCHANGE_EVENTOS_MOODLE, true, false);
        DirectExchange dlx = new DirectExchange(EXCHANGE_DLX, true, false);

        Queue turmaAlterada = comDlq(FILA_TURMA_ALTERADA);
        Queue turmaAlteradaDlq = new Queue(FILA_TURMA_ALTERADA + ".dlq", true);
        Queue turmaSincronizada = comDlq(FILA_TURMA_SINCRONIZADA);
        Queue turmaSincronizadaDlq = new Queue(FILA_TURMA_SINCRONIZADA + ".dlq", true);

        return new Declarables(eventosIeducar, comandos, eventosMoodle, dlx,
                turmaAlterada, turmaAlteradaDlq, turmaSincronizada, turmaSincronizadaDlq,
                BindingBuilder.bind(turmaAlterada).to(eventosIeducar).with(RK_TURMA_ALTERADA),
                BindingBuilder.bind(turmaSincronizada).to(eventosMoodle).with(RK_TURMA_SINCRONIZADA),
                dlqBinding(turmaAlteradaDlq, dlx), dlqBinding(turmaSincronizadaDlq, dlx));
    }

    /** Fila principal: mensagem rejeitada (sem requeue) é encaminhada ao DLX com rk = nome da DLQ. */
    private static Queue comDlq(String nome) {
        return QueueBuilder.durable(nome)
                .deadLetterExchange(EXCHANGE_DLX)
                .deadLetterRoutingKey(nome + ".dlq")
                .build();
    }

    private static Binding dlqBinding(Queue dlq, DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(dlq.getName());
    }

    @Bean
    PoliticaDeTentativas politicaDeTentativas(@Value("${sincronizador.retry.max-tentativas:3}") int max,
                                              @Value("${sincronizador.retry.backoff:PT1S}") Duration backoff) {
        return new PoliticaDeTentativas(max, backoff);
    }
}
