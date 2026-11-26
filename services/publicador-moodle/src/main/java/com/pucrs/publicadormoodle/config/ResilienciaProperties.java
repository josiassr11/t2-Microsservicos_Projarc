package com.pucrs.publicadormoodle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Retry (backoff exponencial) e circuit breaker nas chamadas ao Moodle. */
@ConfigurationProperties(prefix = "resiliencia")
public record ResilienciaProperties(int retryTentativas, Duration retryIntervalo, double retryMultiplicador,
                                    int cbJanela, int cbMinimoChamadas, float cbLimiteFalha,
                                    Duration cbTempoAberto, int cbChamadasMeioAberto) {
}
