package com.pucrs.monitorieducar.config;

import com.pucrs.monitorieducar.adapter.out.ieducar.IEducarApiClient;
import com.pucrs.monitorieducar.adapter.out.ieducar.Resiliencia;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class IEducarConfig {

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
    Resiliencia resilienciaIEducar(ResilienciaProperties props, CircuitBreakerRegistry circuitos, RetryRegistry retries) {
        return Resiliencia.criar("iEducar", props, circuitos, retries);
    }

    @Bean
    IEducarApiClient iEducarApiClient(IEducarProperties props, ResilienciaProperties res, Resiliencia resiliencia) {
        return new IEducarApiClient(restClient(props.baseUrl(), res.timeoutConexao(), res.timeoutLeitura()), props,
                resiliencia);
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
