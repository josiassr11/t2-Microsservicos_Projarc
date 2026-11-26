package com.pucrs.monitorieducar.domain.port.out;

import com.pucrs.monitorieducar.domain.model.ResultadoVarredura;
import com.pucrs.monitorieducar.domain.model.TipoVarredura;

import java.time.Instant;
import java.util.Optional;

public interface VarreduraRepositoryPort {

    long iniciar(String correlationId, TipoVarredura tipo, Instant inicio);

    void concluir(long id, ResultadoVarredura resultado);

    Optional<ResultadoVarredura> ultima();
}
