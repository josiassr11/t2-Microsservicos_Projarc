package com.pucrs.sincronizador.domain.port.out;

import com.pucrs.sincronizador.domain.model.ComandoSincronizar;

public interface ComandoPublisher {

    /** Publica e espera a confirmação do broker. @throws com.pucrs.sincronizador.domain.exception.PublicacaoException se falhar */
    void publicar(ComandoSincronizar comando);
}
