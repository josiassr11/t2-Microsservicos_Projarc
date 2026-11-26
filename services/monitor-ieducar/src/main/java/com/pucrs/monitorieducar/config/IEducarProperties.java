package com.pucrs.monitorieducar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ieducar")
public record IEducarProperties(String baseUrl, String token, String accessKey, String secretKey, int instituicaoId) {
}
