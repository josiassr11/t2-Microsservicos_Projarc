package com.pucrs.sincronizador.adapter;

import com.pucrs.sincronizador.adapter.in.messaging.EnvelopeParser;
import com.pucrs.sincronizador.adapter.in.messaging.PoliticaDeTentativas;
import com.pucrs.sincronizador.adapter.in.messaging.TurmaAlteradaListener;
import com.pucrs.sincronizador.config.JsonConfig;
import com.pucrs.sincronizador.domain.exception.PublicacaoException;
import com.pucrs.sincronizador.domain.model.EventoTurmaAlterada;
import com.pucrs.sincronizador.domain.model.ResultadoProcessamento;
import com.pucrs.sincronizador.domain.model.ResultadoSincronizacao;
import com.pucrs.sincronizador.domain.port.in.ProcessarMensagensUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurmaAlteradaListenerTest {

    static final Message VALIDA = MessageBuilder.withBody(EnvelopeParserTest.turmaAlterada("")
            .getBytes(StandardCharsets.UTF_8)).build();

    final AtomicInteger chamadas = new AtomicInteger();
    final SimpleMeterRegistry metricas = new SimpleMeterRegistry();

    TurmaAlteradaListener listener(int falhasAntesDeFuncionar) {
        ProcessarMensagensUseCase useCase = new ProcessarMensagensUseCase() {
            @Override
            public ResultadoProcessamento processarTurmaAlterada(EventoTurmaAlterada evento) {
                if (chamadas.incrementAndGet() <= falhasAntesDeFuncionar) {
                    throw new PublicacaoException("broker fora do ar");
                }
                return ResultadoProcessamento.PROCESSADA;
            }

            @Override
            public ResultadoProcessamento processarResultado(ResultadoSincronizacao resultado) {
                throw new UnsupportedOperationException();
            }
        };
        return new TurmaAlteradaListener(new EnvelopeParser(new JsonConfig().jsonMapper()), useCase,
                new PoliticaDeTentativas(3, Duration.ZERO), metricas);
    }

    @Test
    void falhaTransitoria_eRepetidaEDepoisFunciona() {
        listener(2).receber(VALIDA);

        assertThat(chamadas.get()).isEqualTo(3);
        assertThat(metricas.counter("integracao.mensagens.consumidas", "tipo", "TurmaAlterada", "resultado", "processada")
                .count()).isEqualTo(1.0);
    }

    @Test
    void esgotouAsTentativas_rejeitaSemRequeue_paraIrParaADlq() {
        assertThatThrownBy(() -> listener(99).receber(VALIDA))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThat(chamadas.get()).isEqualTo(3);
        assertThat(metricas.counter("integracao.mensagens.consumidas", "tipo", "TurmaAlterada", "resultado", "dlq")
                .count()).isEqualTo(1.0);
    }

    @Test
    void poison_vaiDireitoParaADlq_semTentativas() {
        Message lixo = MessageBuilder.withBody("%%% lixo %%%".getBytes(StandardCharsets.UTF_8)).build();

        assertThatThrownBy(() -> listener(0).receber(lixo)).isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThat(chamadas.get()).isZero();
    }
}
