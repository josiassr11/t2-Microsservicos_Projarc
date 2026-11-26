package com.pucrs.sincronizador.domain.model;

import java.util.UUID;

/** {@code TurmaAlterada.v1} já interpretado. */
public record EventoTurmaAlterada(UUID eventId, String correlationId, String mudanca, DadosTurma dados) {
}
