package com.pucrs.monitorieducar.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RabbitConfig {

    public static final String EXCHANGE_EVENTOS = "ieducar.eventos";
    public static final String ROUTING_KEY_TURMA_ALTERADA = "turma.alterada";

    /**
     * O monitor só <b>publica</b>: declara o exchange (idempotente) e deixa as filas para quem consome. Se ninguém
     * tiver fila ligada, a mensagem volta como "unroutable" e conta como falha de publicação.
     */
    @Bean
    TopicExchange ieducarEventos() {
        return new TopicExchange(EXCHANGE_EVENTOS, true, false);
    }
}
