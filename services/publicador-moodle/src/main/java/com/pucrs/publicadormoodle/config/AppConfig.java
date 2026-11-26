package com.pucrs.publicadormoodle.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import com.pucrs.publicadormoodle.adapter.out.moodle.FalhaInjetavel;
import com.pucrs.publicadormoodle.adapter.out.moodle.MoodleResiliente;
import com.pucrs.publicadormoodle.adapter.out.moodle.MoodleWsClient;
import com.pucrs.publicadormoodle.application.OpcoesMoodle;
import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import com.pucrs.publicadormoodle.domain.port.out.MoodlePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    OpcoesMoodle opcoesMoodle(MoodleProperties m) {
        return new OpcoesMoodle(m.papelAlunoId(), m.papelProfessorId(), m.senhaInicial(), m.dominioEmail());
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    RetryRegistry retryRegistry(MeterRegistry meters) {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    CircuitBreaker moodleCircuitBreaker(CircuitBreakerRegistry registry, ResilienciaProperties p) {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.cbJanela())
                .minimumNumberOfCalls(p.cbMinimoChamadas())
                .failureRateThreshold(p.cbLimiteFalha())
                .waitDurationInOpenState(p.cbTempoAberto())
                .permittedNumberOfCallsInHalfOpenState(p.cbChamadasMeioAberto())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // só indisponibilidade conta como falha; recusa de negócio do Moodle não abre o circuito
                .recordException(e -> e instanceof SistemaIndisponivelException)
                .build();
        CircuitBreaker cb = registry.circuitBreaker("moodle", cfg);
        cb.getEventPublisher().onStateTransition(e ->
                log.warn("[CIRCUITO] Moodle: {} -> {}", e.getStateTransition().getFromState(), e.getStateTransition().getToState()));
        return cb;
    }

    @Bean
    Retry moodleRetry(RetryRegistry registry, ResilienciaProperties p) {
        RetryConfig cfg = RetryConfig.custom()
                .maxAttempts(p.retryTentativas())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(p.retryIntervalo(), p.retryMultiplicador()))
                .retryOnException(e -> e instanceof SistemaIndisponivelException)
                .build();
        Retry retry = registry.retry("moodle", cfg);
        retry.getEventPublisher().onRetry(e -> log.warn("[RETRY] chamada ao Moodle falhou (tentativa {}/{}): {}; nova tentativa em {} ms",
                e.getNumberOfRetryAttempts(), p.retryTentativas(), e.getLastThrowable().getMessage(),
                e.getWaitInterval().toMillis()));
        return retry;
    }

    @Bean
    MoodlePort moodlePort(MoodleProperties m, FalhaInjetavel falha, CircuitBreaker moodleCircuitBreaker, Retry moodleRetry) {
        MoodleWsClient http = new MoodleWsClient(restClient(m.baseUrl(), m.timeoutConexao(), m.timeoutLeitura()), m.token());
        return new MoodleResiliente(http, falha, moodleCircuitBreaker, moodleRetry);
    }

    private static RestClient restClient(String baseUrl, Duration conexao, Duration leitura) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(conexao)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(leitura);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
