package com.pucrs.sincronizador.adapter.in.messaging;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record Envelope(UUID eventId, String tipo, int versao, String correlationId, String origem, JsonNode dados) {
}
