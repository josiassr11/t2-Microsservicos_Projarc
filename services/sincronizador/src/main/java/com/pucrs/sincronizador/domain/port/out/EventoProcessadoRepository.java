package com.pucrs.sincronizador.domain.port.out;

import java.util.UUID;

public interface EventoProcessadoRepository {

    /** @return {@code true} se o evento é novo; {@code false} se já havia sido processado (duplicata). */
    boolean registrar(UUID eventId, String tipo, String correlationId);
}
