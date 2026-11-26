package com.pucrs.sincronizador.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** O mesmo evento sempre gera o mesmo comando: reentrega não cria comando "novo". */
public final class IdsDeterministicos {

    private IdsDeterministicos() {
    }

    public static UUID commandId(UUID eventIdOrigem) {
        return UUID.nameUUIDFromBytes(("cmd:" + eventIdOrigem).getBytes(StandardCharsets.UTF_8));
    }
}
