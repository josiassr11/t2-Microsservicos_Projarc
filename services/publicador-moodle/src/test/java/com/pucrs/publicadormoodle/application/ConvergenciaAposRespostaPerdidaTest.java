package com.pucrs.publicadormoodle.application;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import com.pucrs.publicadormoodle.adapter.out.moodle.FalhaInjetavel;
import com.pucrs.publicadormoodle.adapter.out.moodle.MoodleResiliente;
import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import com.pucrs.publicadormoodle.domain.model.Participante;
import com.pucrs.publicadormoodle.domain.model.StatusSincronizacao;
import com.pucrs.publicadormoodle.domain.port.out.Destino;
import com.pucrs.publicadormoodle.application.ProcessadorComandoTest.MensageriaFake;
import com.pucrs.publicadormoodle.application.ProcessadorComandoTest.RepositorioFake;
import com.pucrs.publicadormoodle.application.ProcessadorComandoTest.Reenvio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.pucrs.publicadormoodle.application.Fixtures.aluno;
import static com.pucrs.publicadormoodle.application.Fixtures.comando;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cenário "o Moodle cria o curso mas a resposta não chega": o fluxo completo (processador + sincronizador + decorador
 * resiliente + Moodle em memória) é reexecutado pela política de retry por fila e CONVERGE sem duplicar nada.
 */
class ConvergenciaAposRespostaPerdidaTest {

    FakeMoodle moodle;
    FalhaInjetavel falha;
    RepositorioFake repo;
    MensageriaFake msgs;
    ProcessadorComando processador;

    @BeforeEach
    void preparar() {
        moodle = new FakeMoodle();
        falha = new FalhaInjetavel(true);
        repo = new RepositorioFake();
        msgs = new MensageriaFake();
        CircuitBreaker cb = CircuitBreaker.of("t", CircuitBreakerConfig.custom().slidingWindowSize(50).minimumNumberOfCalls(50)
                .recordException(e -> e instanceof SistemaIndisponivelException).build());
        Retry retry = Retry.of("t", RetryConfig.custom().maxAttempts(3).intervalFunction(IntervalFunction.of(Duration.ofMillis(1)))
                .retryOnException(e -> e instanceof SistemaIndisponivelException).build());
        var moodleResiliente = new MoodleResiliente(moodle, falha, cb, retry);
        processador = new ProcessadorComando(new SincronizadorTurma(moodleResiliente,
                new OpcoesMoodle(5, 3, "x", "exemplo.edu.br")), repo, msgs, r -> { });
    }

    ComandoRecebido cmd() {
        return new ComandoRecebido(UUID.randomUUID(), "corr", comando(1, true,
                List.of(aluno(7, "Ana Silva", "ana@x.com", true), aluno(8, "Bruno Lima", null, true)),
                List.of(new Participante(2, "Carlos Souza", null, true))), "{}".getBytes());
    }

    @Test
    void criacaoComRespostaPerdidaNaoEhRepetidaNaChamadaEConvergeNoRetryPorFila() {
        falha.definirPerderRespostaNaCriacao(1); // a 1ª criação (a categoria) acontece no Moodle, mas a resposta se perde
        ComandoRecebido c = cmd();

        processador.processar(c, 1);

        assertThat(moodle.categorias).hasSize(1);          // foi criada de verdade...
        assertThat(moodle.chamadasCriar).isEqualTo(1);     // ...e NÃO foi repetida dentro da chamada
        assertThat(msgs.reenfileirados).containsExactly(new Reenvio(Destino.RETRY_5S, 2));
        assertThat(msgs.resultados).isEmpty();

        processador.processar(c, 2); // o retry por fila reexecuta o fluxo: a busca por idnumber acha a categoria e segue

        assertThat(msgs.resultados).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(StatusSincronizacao.SUCESSO);
            assertThat(r.erros()).isEmpty();
            assertThat(r.tentativas()).isEqualTo(2);
        });
        assertThat(moodle.categorias).hasSize(1);
        assertThat(moodle.cursos).hasSize(1);
        assertThat(moodle.usuarios).hasSize(3);
        assertThat(moodle.chamadasCriar).isEqualTo(1 + 1 + 3); // categoria + curso + 3 usuários, cada um uma única vez
    }

    @Test
    void respostaPerdidaNaCriacaoDoCursoTambemConverge() {
        ComandoRecebido c = cmd();
        // deixa a categoria passar; o curso (2ª criação) perde a resposta
        moodle.criarCategoria("Engenharia de Software", "IEDUCAR-CURSO-3");
        int antes = moodle.chamadasCriar;
        falha.definirPerderRespostaNaCriacao(1);

        processador.processar(c, 1);
        assertThat(moodle.cursos).hasSize(1);
        assertThat(msgs.resultados).isEmpty();

        processador.processar(c, 2);

        assertThat(msgs.resultados).singleElement().extracting(r -> r.status()).isEqualTo(StatusSincronizacao.SUCESSO);
        assertThat(moodle.cursos).hasSize(1);
        assertThat(moodle.chamadasCriar).isEqualTo(antes + 1 + 3);
    }

    @Test
    void respostaPerdidaNaCriacaoDeUsuarioConvergeSemUsuarioDuplicado() {
        ComandoRecebido c = cmd();
        List<String> falhasNoCurso = new ArrayList<>();
        processador.processar(c, 1); // fluxo normal cria tudo
        moodle.usuarios.remove("IEDUCAR-ALUNO-8");     // simula um usuário que ainda não existia
        moodle.emailsUsados.remove("aluno8@exemplo.edu.br");
        falha.definirPerderRespostaNaCriacao(1);
        ComandoRecebido c2 = new ComandoRecebido(UUID.randomUUID(), "corr", comando(2, true,
                c.comando().alunos(), c.comando().professores()), "{}".getBytes());

        processador.processar(c2, 1);   // cria o aluno 8, resposta perdida
        assertThat(moodle.usuarios).containsKey("IEDUCAR-ALUNO-8");
        processador.processar(c2, 2);   // reexecução: encontra o aluno 8, só garante a inscrição

        assertThat(msgs.resultados).last().extracting(r -> r.status()).isEqualTo(StatusSincronizacao.SUCESSO);
        assertThat(moodle.usuarios).hasSize(3);
        assertThat(falhasNoCurso).isEmpty();
    }
}
