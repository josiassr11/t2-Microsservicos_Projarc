package com.pucrs.monitorieducar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code resiliencia.*}: timeouts, retry e circuit breaker das chamadas ao iEducar. */
@ConfigurationProperties(prefix = "resiliencia")
public record ResilienciaProperties(Duration timeoutConexao, Duration timeoutLeitura, Retry retry, Circuito circuito) {

    public record Retry(int maxTentativas, Duration espera, double multiplicador) {
    }

    public record Circuito(int janela, int minimoChamadas, int taxaFalhaPercentual, Duration esperaAberto,
                           int chamadasMeioAberto) {
    }
}
