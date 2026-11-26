package com.pucrs.monitorieducar.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Dados necessários para publicar um {@code TurmaAlterada.v1}. */
public record EventoTurmaAlterada(UUID eventId, UUID correlationId, Instant ocorridoEm, TipoMudanca mudanca,
                                  String hash, TurmaSnapshot snapshot) {
}
