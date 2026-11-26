package com.pucrs.monitorieducar.adapter.out.metrics;

import com.pucrs.monitorieducar.domain.model.StatusVarredura;
import com.pucrs.monitorieducar.domain.port.out.MetricasPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MicrometerMetricas implements MetricasPort {

    private final MeterRegistry meters;

    public MicrometerMetricas(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void varreduraConcluida(StatusVarredura status) {
        meters.counter("monitor.varreduras", "status", status.name()).increment();
    }
}
