package com.pucrs.monitorieducar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code monitor.*}: agendamento da varredura e espera pelo confirm do broker. */
@ConfigurationProperties(prefix = "monitor")
public record MonitorProperties(boolean agendado, Duration intervalo, Duration atrasoInicial,
                                Duration confirmTimeout) {
}
