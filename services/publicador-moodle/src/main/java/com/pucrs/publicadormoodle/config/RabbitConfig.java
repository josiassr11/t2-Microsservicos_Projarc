package com.pucrs.publicadormoodle.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia deste serviço. O consumidor declara a própria fila (e o exchange, de forma idempotente).
 * As filas de retry não têm consumidor: a mensagem espera o TTL e o dead-letter a devolve à fila principal.
 */
@Configuration
public class RabbitConfig {

    @Bean
    DirectExchange comandosExchange() {
        return new DirectExchange(Topologia.EXCHANGE_COMANDOS, true, false);
    }

    @Bean
    TopicExchange resultadosExchange() {
        return new TopicExchange(Topologia.EXCHANGE_RESULTADOS, true, false);
    }

    @Bean
    Queue filaComandos() {
        return QueueBuilder.durable(Topologia.FILA_COMANDOS).build();
    }

    @Bean
    Binding bindingComandos(Queue filaComandos, DirectExchange comandosExchange) {
        return BindingBuilder.bind(filaComandos).to(comandosExchange).with(Topologia.RK_COMANDO);
    }

    @Bean
    Queue filaRetry5s() {
        return retry(Topologia.FILA_RETRY_5S, 5_000);
    }

    @Bean
    Queue filaRetry30s() {
        return retry(Topologia.FILA_RETRY_30S, 30_000);
    }

    @Bean
    Queue filaDlq() {
        return QueueBuilder.durable(Topologia.FILA_DLQ).build();
    }

    /** Fila de espera: TTL e, ao expirar, dead-letter no exchange padrão com a routing key da fila principal. */
    private static Queue retry(String nome, int ttlMs) {
        return QueueBuilder.durable(nome)
                .ttl(ttlMs)
                .deadLetterExchange("")
                .deadLetterRoutingKey(Topologia.FILA_COMANDOS)
                .build();
    }
}
