package com.pucrs.sincronizador.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonConfig {

    /** Leitor tolerante: campo novo e desconhecido numa mensagem NÃO quebra o consumidor. */
    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
