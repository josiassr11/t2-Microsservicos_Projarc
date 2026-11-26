package com.pucrs.publicadormoodle.adapter;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import com.pucrs.publicadormoodle.adapter.out.moodle.FalhaInjetavel;
import com.pucrs.publicadormoodle.adapter.out.moodle.MoodleResiliente;
import com.pucrs.publicadormoodle.domain.exception.MoodleException;
import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import com.pucrs.publicadormoodle.domain.model.CursoMoodle;
import com.pucrs.publicadormoodle.domain.model.NovoUsuarioMoodle;
import com.pucrs.publicadormoodle.domain.model.UsuarioMoodle;
import com.pucrs.publicadormoodle.domain.port.out.MoodlePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoodleResilienteTest {

    /** Moodle de mentira: falha as N primeiras chamadas de buscarCategoria. */
    static class MoodleInstavel implements MoodlePort {
        final AtomicInteger chamadas = new AtomicInteger();
        volatile int falhasRestantes;
        volatile boolean negocio;
        final AtomicInteger criacoes = new AtomicInteger();
        volatile int criacoesQueFalhamDepoisDoEfeito;

        @Override
        public Optional<Long> buscarCategoria(String idNumber) {
            chamadas.incrementAndGet();
            if (negocio) throw new MoodleException("invalidparameter", "recusado");
            if (falhasRestantes-- > 0) throw new SistemaIndisponivelException("Moodle", "timeout");
            return Optional.of(9L);
        }

        @Override
        public long criarCategoria(String nome, String idNumber) {
            criacoes.incrementAndGet(); // o Moodle "criou"
            if (criacoesQueFalhamDepoisDoEfeito-- > 0) throw new SistemaIndisponivelException("Moodle", "timeout lendo a resposta");
            return 5;
        }
        @Override public Optional<CursoMoodle> buscarCurso(String idNumber) { return Optional.empty(); }
        @Override public long criarCurso(String a, String b, String c, long d, boolean e) { return 0; }
        @Override public void atualizarCurso(long cursoId, String nomeCompleto, boolean visivel) { }
        @Override public Optional<UsuarioMoodle> buscarUsuario(String idNumber) { return Optional.empty(); }
        @Override public long criarUsuario(NovoUsuarioMoodle usuario) { return 0; }
        @Override public void atualizarUsuario(long id, String a, String b, String c) { }
        @Override public Set<Long> listarInscritosAtivos(long cursoId) { return Set.of(); }
        @Override public void inscrever(long c, long u, long p, boolean s) { }
    }

    MoodleInstavel moodle;
    FalhaInjetavel falha;
    CircuitBreaker circuito;
    MoodleResiliente resiliente;

    @BeforeEach
    void preparar() {
        moodle = new MoodleInstavel();
        falha = new FalhaInjetavel(true);
        circuito = CircuitBreaker.of("t", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .recordException(e -> e instanceof SistemaIndisponivelException).build());
        Retry retry = Retry.of("t", RetryConfig.custom().maxAttempts(3)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(1)))
                .retryOnException(e -> e instanceof SistemaIndisponivelException).build());
        resiliente = new MoodleResiliente(moodle, falha, circuito, retry);
    }

    @Test
    void falhaTransitoriaEhRepetidaEDepoisDaCerto() {
        moodle.falhasRestantes = 2;

        assertThat(resiliente.buscarCategoria("x")).contains(9L);
        assertThat(moodle.chamadas.get()).isEqualTo(3);
    }

    @Test
    void depoisDeEsgotarOsRetriesPropagaIndisponivel() {
        moodle.falhasRestantes = 99;

        assertThatThrownBy(() -> resiliente.buscarCategoria("x")).isInstanceOf(SistemaIndisponivelException.class);
        assertThat(moodle.chamadas.get()).isEqualTo(3);
    }

    @Test
    void recusaDeNegocioNaoEhRepetidaENaoAbreOCircuito() {
        moodle.negocio = true;

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> resiliente.buscarCategoria("x")).isInstanceOf(MoodleException.class);
        }
        assertThat(moodle.chamadas.get()).isEqualTo(6); // sem retry
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void circuitoAbreEDepoisFalhaRapidoSemChamarOMoodle() {
        moodle.falhasRestantes = 99;
        assertThatThrownBy(() -> resiliente.buscarCategoria("x")).isInstanceOf(SistemaIndisponivelException.class); // 3 falhas
        assertThatThrownBy(() -> resiliente.buscarCategoria("x")).isInstanceOf(SistemaIndisponivelException.class); // 4ª falha abre

        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int antes = moodle.chamadas.get();
        assertThatThrownBy(() -> resiliente.buscarCategoria("x"))
                .isInstanceOf(SistemaIndisponivelException.class).hasMessageContaining("circuito aberto");
        assertThat(moodle.chamadas.get()).isEqualTo(antes);
    }

    @Test
    void falhaInjetadaSimulaIndisponibilidadeAntesDoHttp() {
        falha.definirIndisponivel(true);

        assertThatThrownBy(() -> resiliente.buscarCategoria("x")).isInstanceOf(SistemaIndisponivelException.class)
                .hasMessageContaining("falha injetada");
        assertThat(moodle.chamadas.get()).isZero();

        falha.definirIndisponivel(false);
        assertThat(resiliente.buscarCategoria("x")).contains(9L);
    }

    @Test
    void falhaInjetavelDesligadaNaoPodeSerLigada() {
        FalhaInjetavel desligada = new FalhaInjetavel(false);

        assertThat(desligada.indisponivel()).isFalse();
        assertThatThrownBy(() -> desligada.definirIndisponivel(true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void criacaoNaoTemRetryDentroDaChamada() {
        moodle.criacoesQueFalhamDepoisDoEfeito = 1;

        // o Moodle criou, a resposta se perdeu: repetir daria "duplicado"; a falha sobe para o retry por fila
        assertThatThrownBy(() -> resiliente.criarCategoria("n", "i")).isInstanceOf(SistemaIndisponivelException.class);
        assertThat(moodle.criacoes.get()).isEqualTo(1);
    }

    @Test
    void leituraContinuaComRetryEcriacaoContinuaPassandoPeloCircuito() {
        moodle.criacoesQueFalhamDepoisDoEfeito = 99;
        assertThatThrownBy(() -> resiliente.criarCategoria("n", "i")).isInstanceOf(SistemaIndisponivelException.class);
        assertThatThrownBy(() -> resiliente.criarCategoria("n", "i")).isInstanceOf(SistemaIndisponivelException.class);
        assertThatThrownBy(() -> resiliente.criarCategoria("n", "i")).isInstanceOf(SistemaIndisponivelException.class);
        assertThatThrownBy(() -> resiliente.criarCategoria("n", "i")).isInstanceOf(SistemaIndisponivelException.class);

        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN); // 4 falhas de criação abrem o circuito
        assertThat(moodle.criacoes.get()).isEqualTo(4);
        assertThatThrownBy(() -> resiliente.criarCategoria("n", "i")).hasMessageContaining("circuito aberto");
        assertThat(moodle.criacoes.get()).isEqualTo(4);
    }

    @Test
    void perderRespostaNaCriacaoExecutaDeVerdadeEDepoisFalhaNVezes() {
        falha.definirPerderRespostaNaCriacao(2);

        assertThatThrownBy(() -> resiliente.criarCategoria("n", "i")).isInstanceOf(SistemaIndisponivelException.class)
                .hasMessageContaining("resposta perdida");
        assertThatThrownBy(() -> resiliente.criarCategoria("n", "i")).isInstanceOf(SistemaIndisponivelException.class);
        assertThat(moodle.criacoes.get()).isEqualTo(2); // executadas de verdade
        assertThat(falha.perderRespostaNaCriacao()).isZero();

        assertThat(resiliente.criarCategoria("n", "i")).isEqualTo(5); // cota acabou: volta ao normal
        assertThat(moodle.criacoes.get()).isEqualTo(3);
    }

    @Test
    void perderRespostaNaoAfetaLeituras() {
        falha.definirPerderRespostaNaCriacao(1);

        assertThat(resiliente.buscarCategoria("x")).contains(9L);
        assertThat(falha.perderRespostaNaCriacao()).isEqualTo(1);
    }

    @Test
    void latenciaInjetadaAtrasaCadaChamada() {
        falha.definirLatenciaMs(60);

        long inicio = System.nanoTime();
        resiliente.buscarCategoria("x");
        resiliente.buscarCategoria("x");
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(ms).isGreaterThanOrEqualTo(110);
        assertThat(falha.estado()).containsEntry("latenciaMs", 60L).containsEntry("perderRespostaNaCriacao", 0);

        falha.definirLatenciaMs(0);
        assertThat(falha.latenciaMs()).isZero();
    }

    @Test
    void injecoesDesligadasNaoPodemSerLigadas() {
        FalhaInjetavel desligada = new FalhaInjetavel(false);

        assertThatThrownBy(() -> desligada.definirPerderRespostaNaCriacao(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> desligada.definirLatenciaMs(10)).isInstanceOf(IllegalStateException.class);
        assertThat(desligada.estado()).containsEntry("habilitada", false).containsEntry("perderRespostaNaCriacao", 0);
    }
}
