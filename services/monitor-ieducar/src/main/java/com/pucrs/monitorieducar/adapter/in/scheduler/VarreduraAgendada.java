package com.pucrs.monitorieducar.adapter.in.scheduler;

import com.pucrs.monitorieducar.domain.exception.VarreduraEmAndamentoException;
import com.pucrs.monitorieducar.domain.model.TipoVarredura;
import com.pucrs.monitorieducar.domain.port.in.VarreduraUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dispara a varredura incremental a cada {@code monitor.intervalo} (desligável com {@code monitor.agendado=false}). */
@Component
@ConditionalOnProperty(name = "monitor.agendado", havingValue = "true", matchIfMissing = true)
public class VarreduraAgendada {

    private static final Logger log = LoggerFactory.getLogger(VarreduraAgendada.class);

    private final VarreduraUseCase varredura;

    public VarreduraAgendada(VarreduraUseCase varredura) {
        this.varredura = varredura;
    }

    @Scheduled(fixedDelayString = "${monitor.intervalo}", initialDelayString = "${monitor.atraso-inicial}")
    public void executar() {
        try {
            varredura.varrer(TipoVarredura.INCREMENTAL);
        } catch (VarreduraEmAndamentoException e) {
            log.info("[VARREDURA] agendada ignorada: já existe uma varredura em andamento");
        } catch (RuntimeException e) {
            log.error("[VARREDURA] erro inesperado na varredura agendada", e);
        }
    }
}
