package com.pucrs.publicadormoodle.config;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** JSON das mensagens: consumidor tolerante (ignora campos desconhecidos). */
public final class JsonSupport {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonSupport() {
    }
}
