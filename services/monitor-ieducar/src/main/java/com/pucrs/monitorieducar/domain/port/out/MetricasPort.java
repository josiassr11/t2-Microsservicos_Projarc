package com.pucrs.monitorieducar.domain.port.out;

import com.pucrs.monitorieducar.domain.model.StatusVarredura;

public interface MetricasPort {

    void varreduraConcluida(StatusVarredura status);
}
