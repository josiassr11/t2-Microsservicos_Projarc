package com.pucrs.sincronizador.domain.model;

import java.util.UUID;

/** {@code SincronizarTurma.v1}: o que o publicador-moodle deve aplicar. */
public record ComandoSincronizar(UUID commandId, String correlationId, int versao, DadosTurma dados) {
}
