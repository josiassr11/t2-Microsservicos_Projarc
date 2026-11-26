package com.pucrs.publicadormoodle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "moodle")
public record MoodleProperties(String baseUrl, String token, long papelAlunoId, long papelProfessorId,
                               String senhaInicial, String dominioEmail, Duration timeoutConexao,
                               Duration timeoutLeitura) {
}
