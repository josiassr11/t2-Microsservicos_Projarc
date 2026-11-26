package com.pucrs.monitorieducar.adapter;

import com.pucrs.monitorieducar.adapter.out.ieducar.Resiliencia;
import com.pucrs.monitorieducar.config.ResilienciaProperties;
import com.pucrs.monitorieducar.domain.exception.SistemaExternoException;
import com.pucrs.monitorieducar.domain.exception.SistemaIndisponivelException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilienciaTest {

    static Resiliencia criar(int tentativas, int janela, int minimo, Duration esperaAberto) {
        var props = new ResilienciaProperties(Duration.ofSeconds(1), Duration.ofSeconds(1),
                new ResilienciaProperties.Retry(tentativas, Duration.ofMillis(5), 2.0),
                new ResilienciaProperties.Circuito(janela, minimo, 50, esperaAberto, 2));
        return Resiliencia.criar("iEducar", props, CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    }

    @Test
    void retryRepeteAteOMaximoDeTentativas() {
        Resiliencia r = criar(3, 100, 100, Duration.ofSeconds(30));
        AtomicInteger chamadas = new AtomicInteger();

        assertThatThrownBy(() -> r.executar(() -> {
            chamadas.incrementAndGet();
            throw new SistemaIndisponivelException("iEducar", "timeout");
        })).isInstanceOf(SistemaIndisponivelException.class).hasMessageContaining("timeout");

        assertThat(chamadas).hasValue(3);
    }

    @Test
    void retrySucedeNaSegundaTentativa() {
        Resiliencia r = criar(3, 100, 100, Duration.ofSeconds(30));
        AtomicInteger chamadas = new AtomicInteger();

        String resultado = r.executar(() -> {
            if (chamadas.incrementAndGet() < 2) {
                throw new SistemaIndisponivelException("iEducar", "oscilação");
            }
            return "ok";
        });

        assertThat(resultado).isEqualTo("ok");
        assertThat(chamadas).hasValue(2);
    }

    @Test
    void erroDeNegocioNaoEhRepetido() {
        Resiliencia r = criar(3, 100, 100, Duration.ofSeconds(30));
        AtomicInteger chamadas = new AtomicInteger();

        assertThatThrownBy(() -> r.executar(() -> {
            chamadas.incrementAndGet();
            throw new SistemaExternoException("iEducar", "resposta inválida");
        })).isInstanceOf(SistemaExternoException.class);

        assertThat(chamadas).hasValue(1);
    }

    @Test
    void circuitoAbreDepoisDeFalhasERecusaSemChamarARede() {
        Resiliencia r = criar(1, 4, 4, Duration.ofSeconds(30));
        AtomicInteger chamadas = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> r.executar(() -> {
                chamadas.incrementAndGet();
                throw new SistemaIndisponivelException("iEducar", "connection refused");
            })).isInstanceOf(SistemaIndisponivelException.class);
        }
        assertThat(r.circuito().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> r.executar(() -> {
            chamadas.incrementAndGet();
            return "não deveria chamar";
        })).isInstanceOf(SistemaIndisponivelException.class).hasMessageContaining("circuito aberto");

        assertThat(chamadas).as("a 5ª chamada nem chegou à rede").hasValue(4);
    }

    @Test
    void circuitoVoltaAFecharQuandoOSistemaRecupera() throws Exception {
        Resiliencia r = criar(1, 4, 4, Duration.ofMillis(100));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> r.executar(() -> {
                throw new SistemaIndisponivelException("iEducar", "caiu");
            })).isInstanceOf(SistemaIndisponivelException.class);
        }
        assertThat(r.circuito().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(150);                                       // espera o tempo de "aberto"
        r.executar(() -> "volta 1");                            // meio-aberto: 1ª chamada de teste
        r.executar(() -> "volta 2");                            // 2ª chamada de teste -> fecha

        assertThat(r.circuito().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
