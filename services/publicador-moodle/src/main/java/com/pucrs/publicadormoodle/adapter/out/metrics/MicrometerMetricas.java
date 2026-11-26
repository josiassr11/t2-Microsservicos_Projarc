package com.pucrs.publicadormoodle.adapter.out.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import com.pucrs.publicadormoodle.domain.port.out.Metricas;
import org.springframework.stereotype.Component;

@Component
public class MicrometerMetricas implements Metricas {

    private final MeterRegistry registry;

    public MicrometerMetricas(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void comando(String resultado) {
        registry.counter("integracao_comandos_processados_total", "servico", "publicador-moodle", "resultado", resultado)
                .increment();
    }
}
