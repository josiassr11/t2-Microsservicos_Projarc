package com.pucrs.monitorieducar.adapter.out.ieducar;

import com.pucrs.monitorieducar.config.ResilienciaProperties;
import com.pucrs.monitorieducar.domain.exception.SistemaIndisponivelException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Proteção das chamadas a um sistema externo: <b>Retry</b> (repete falhas de indisponibilidade com espera
 * exponencial) por fora e <b>Circuit Breaker</b> (para de chamar um sistema que está falhando) por dentro.
 * Cada tentativa passa pelo circuito; quando ele abre, a chamada é recusada na hora, sem tocar na rede.
 * O timeout fica no cliente HTTP (conexão/leitura).
 */
public class Resiliencia {

    private static final Logger log = LoggerFactory.getLogger(Resiliencia.class);

    private final String sistema;
    private final Retry retry;
    private final CircuitBreaker circuito;

    private Resiliencia(String sistema, Retry retry, CircuitBreaker circuito) {
        this.sistema = sistema;
        this.retry = retry;
        this.circuito = circuito;
    }

    public static Resiliencia criar(String sistema, ResilienciaProperties p, CircuitBreakerRegistry circuitos,
                                    RetryRegistry retries) {
        var r = p.retry();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(r.maxTentativas())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(r.espera().toMillis(), r.multiplicador()))
                .retryExceptions(SistemaIndisponivelException.class)
                .build();
        var c = p.circuito();
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(c.janela())
                .minimumNumberOfCalls(c.minimoChamadas())
                .failureRateThreshold(c.taxaFalhaPercentual())
                .waitDurationInOpenState(c.esperaAberto())
                .permittedNumberOfCallsInHalfOpenState(c.chamadasMeioAberto())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordExceptions(SistemaIndisponivelException.class)
                .build();

        Retry retry = retries.retry("retry-" + sistema, retryConfig);
        CircuitBreaker cb = circuitos.circuitBreaker("circuito-" + sistema, cbConfig);

        retry.getEventPublisher()
                .onRetry(e -> log.warn("[RETRY] {} tentativa {}/{} falhou ({}); nova tentativa em {}ms", sistema,
                        e.getNumberOfRetryAttempts(), r.maxTentativas(), resumo(e.getLastThrowable()),
                        e.getWaitInterval().toMillis()))
                .onError(e -> log.warn("[RETRY] {} esgotou as {} tentativas: {}", sistema, r.maxTentativas(),
                        resumo(e.getLastThrowable())));
        cb.getEventPublisher()
                .onStateTransition(e -> log.warn("[CIRCUITO] {} {} -> {}", sistema,
                        e.getStateTransition().getFromState(), e.getStateTransition().getToState()))
                .onCallNotPermitted(e -> log.warn("[CIRCUITO] {} ABERTO: chamada recusada sem tentar a rede", sistema));
        return new Resiliencia(sistema, retry, cb);
    }

    /** Sem retry nem circuito (testes do mapeamento HTTP). */
    public static Resiliencia semProtecao() {
        return new Resiliencia("iEducar", null, null);
    }

    public <T> T executar(Supplier<T> chamada) {
        if (retry == null) {
            return chamada.get();
        }
        Supplier<T> comCircuito = CircuitBreaker.decorateSupplier(circuito, chamada);
        Supplier<T> comRetry = Retry.decorateSupplier(retry, comCircuito);
        try {
            return comRetry.get();
        } catch (CallNotPermittedException e) {
            throw new SistemaIndisponivelException(sistema, "circuito aberto (chamada recusada sem tentar a rede)", e);
        }
    }

    public CircuitBreaker circuito() {
        return circuito;
    }

    private static String resumo(Throwable t) {
        return t == null ? "?" : t.getMessage();
    }
}
