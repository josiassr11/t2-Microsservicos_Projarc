package com.pucrs.monitorieducar.domain.port.in;

import com.pucrs.monitorieducar.domain.model.RegistroSnapshot;
import com.pucrs.monitorieducar.domain.model.ResultadoVarredura;
import com.pucrs.monitorieducar.domain.model.TipoVarredura;

import java.util.List;
import java.util.Optional;

public interface VarreduraUseCase {

    /** @throws com.pucrs.monitorieducar.domain.exception.VarreduraEmAndamentoException se já houver uma rodando */
    ResultadoVarredura varrer(TipoVarredura tipo);

    Optional<ResultadoVarredura> ultimaVarredura();

    List<RegistroSnapshot> snapshots();
}
