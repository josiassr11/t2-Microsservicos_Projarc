package com.pucrs.monitorieducar.domain.port.out;

import com.pucrs.monitorieducar.domain.model.EventoTurmaAlterada;

/** Publica o evento no broker e só retorna depois da confirmação (publisher confirm). */
public interface PublicadorEventosPort {

    /** @throws com.pucrs.monitorieducar.domain.exception.PublicacaoFalhouException se não houve confirmação */
    void publicar(EventoTurmaAlterada evento);
}
