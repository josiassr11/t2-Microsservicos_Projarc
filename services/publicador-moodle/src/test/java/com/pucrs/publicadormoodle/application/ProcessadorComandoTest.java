package com.pucrs.publicadormoodle.application;

import com.pucrs.publicadormoodle.domain.model.ComandoProcessado;
import com.pucrs.publicadormoodle.domain.model.ResultadoSincronizacao;
import com.pucrs.publicadormoodle.domain.model.StatusSincronizacao;
import com.pucrs.publicadormoodle.domain.model.TurmaComando;
import com.pucrs.publicadormoodle.domain.exception.FalhaDePublicacaoException;
import com.pucrs.publicadormoodle.domain.port.out.ComandoRepositorio;
import com.pucrs.publicadormoodle.domain.port.out.Destino;
import com.pucrs.publicadormoodle.domain.port.out.MensageriaPort;
import com.pucrs.publicadormoodle.domain.port.out.Metricas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.pucrs.publicadormoodle.application.Fixtures.aluno;
import static com.pucrs.publicadormoodle.application.Fixtures.comando;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessadorComandoTest {

    FakeMoodle moodle;
    RepositorioFake repo;
    MensageriaFake msgs;
    List<String> metricas;
    ProcessadorComando processador;

    @BeforeEach
    void preparar() {
        moodle = new FakeMoodle();
        repo = new RepositorioFake();
        msgs = new MensageriaFake();
        metricas = new ArrayList<>();
        Metricas m = metricas::add;
        processador = new ProcessadorComando(new SincronizadorTurma(moodle, new OpcoesMoodle(5, 3, "x", "exemplo.edu.br")),
                repo, msgs, m);
    }

    ComandoRecebido cmd(int versao) {
        TurmaComando c = comando(versao, true, List.of(aluno(7, "Ana Silva", "ana@x.com", true)), List.of());
        return new ComandoRecebido(UUID.randomUUID(), "corr-1", c, "{}".getBytes());
    }

    @Test
    void sucessoGravaNaInboxEPublicaOResultado() {
        ComandoRecebido c = cmd(1);

        processador.processar(c, 1);

        assertThat(repo.itens).containsKey(c.commandId());
        assertThat(msgs.resultados).hasSize(1);
        ResultadoSincronizacao r = msgs.resultados.get(0);
        assertThat(r.status()).isEqualTo(StatusSincronizacao.SUCESSO);
        assertThat(r.tentativas()).isEqualTo(1);
        assertThat(r.mapeamentos()).isNotEmpty();
        assertThat(metricas).containsExactly("sucesso");
    }

    @Test
    void comandoDuplicadoNaoTocaNoMoodleEReemiteOResultadoSalvo() {
        ComandoRecebido c = cmd(1);
        processador.processar(c, 1);
        int chamadas = moodle.chamadas;

        processador.processar(c, 1);

        assertThat(moodle.chamadas).isEqualTo(chamadas);
        assertThat(msgs.resultados).hasSize(2);
        assertThat(msgs.resultados.get(1)).isEqualTo(msgs.resultados.get(0));
        assertThat(metricas).containsExactly("sucesso", "duplicado");
    }

    @Test
    void comandoComVersaoMenorQueAJaAplicadaEhDescartado() {
        processador.processar(cmd(5), 1);
        int chamadas = moodle.chamadas;
        int resultados = msgs.resultados.size();

        processador.processar(cmd(4), 1);

        assertThat(moodle.chamadas).isEqualTo(chamadas);
        assertThat(msgs.resultados).hasSize(resultados);
        assertThat(metricas).last().isEqualTo("obsoleto");
    }

    @Test
    void versaoIgualOuMaiorEhProcessada() {
        processador.processar(cmd(5), 1);
        processador.processar(cmd(6), 1);

        assertThat(repo.itens).hasSize(2);
        assertThat(metricas).containsExactly("sucesso", "sucesso");
    }

    @Test
    void moodleForaNaTentativa1VaiParaRetry5s() {
        moodle.fora = true;
        ComandoRecebido c = cmd(1);

        processador.processar(c, 1);

        assertThat(msgs.reenfileirados).containsExactly(new Reenvio(Destino.RETRY_5S, 2));
        assertThat(msgs.resultados).isEmpty();
        assertThat(repo.itens).isEmpty();
        assertThat(metricas).containsExactly("retry");
    }

    @Test
    void moodleForaNaTentativa2VaiParaRetry30s() {
        moodle.fora = true;

        processador.processar(cmd(1), 2);

        assertThat(msgs.reenfileirados).containsExactly(new Reenvio(Destino.RETRY_30S, 3));
    }

    @Test
    void moodleForaNaTentativa3VaiParaDlqEAvisaOSincronizadorComFalha() {
        moodle.fora = true;
        ComandoRecebido c = cmd(1);

        processador.processar(c, 3);

        assertThat(msgs.reenfileirados).containsExactly(new Reenvio(Destino.DLQ, 3));
        assertThat(msgs.resultados).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(StatusSincronizacao.FALHA);
            assertThat(r.tentativas()).isEqualTo(3);
            assertThat(r.erros()).singleElement().asString().contains("DLQ");
        });
        // não pode ir para a inbox: o reprocessamento da DLQ precisa executar de novo
        assertThat(repo.itens).isEmpty();
        assertThat(metricas).containsExactly("dlq");
    }

    @Test
    void reprocessarDepoisDaDlqFuncionaQuandoOMoodleVolta() {
        moodle.fora = true;
        ComandoRecebido c = cmd(1);
        processador.processar(c, 3);
        moodle.fora = false;

        processador.processar(c, 1); // a DLQ devolve com x-tentativa=1

        assertThat(msgs.resultados).last().extracting(ResultadoSincronizacao::status).isEqualTo(StatusSincronizacao.SUCESSO);
        assertThat(repo.itens).containsKey(c.commandId());
    }

    @Test
    void erroInesperadoTambemSegueAPoliticaDeRetry() {
        FakeMoodle quebrado = new FakeMoodle() {
            @Override public java.util.Optional<com.pucrs.publicadormoodle.domain.model.CursoMoodle> buscarCurso(String idNumber) { throw new IllegalStateException("bug"); }
        };
        ProcessadorComando p = new ProcessadorComando(new SincronizadorTurma(quebrado, new OpcoesMoodle(5, 3, "x", "d.com")), repo, msgs, metricas::add);

        p.processar(cmd(1), 1);

        assertThat(msgs.reenfileirados).containsExactly(new Reenvio(Destino.RETRY_5S, 2));
    }

    @Test
    void seOBrokerNaoConfirmarOResultadoAExcecaoSobeParaARecolocacaoNaFila() {
        msgs.falharPublicacao = true;
        ComandoRecebido c = cmd(1);

        assertThatThrownBy(() -> processador.processar(c, 1)).isInstanceOf(FalhaDePublicacaoException.class);

        // o comando já está na inbox: a reentrega só reemite o resultado, sem refazer o Moodle
        msgs.falharPublicacao = false;
        int chamadas = moodle.chamadas;
        processador.processar(c, 1);
        assertThat(moodle.chamadas).isEqualTo(chamadas);
        assertThat(msgs.resultados).hasSize(1);
    }

    @Test
    void mensagemIlegivelVaiDiretoParaADlq() {
        processador.rejeitarIlegivel("lixo".getBytes(), "m1", "c1", 1, "JSON inválido");

        assertThat(msgs.ilegiveis).containsExactly("JSON inválido");
        assertThat(metricas).containsExactly("ilegivel");
    }

    // ---------------- dublês ----------------

    record Reenvio(Destino destino, int tentativa) {
    }

    static class MensageriaFake implements MensageriaPort {
        final List<ResultadoSincronizacao> resultados = new ArrayList<>();
        final List<Reenvio> reenfileirados = new ArrayList<>();
        final List<String> ilegiveis = new ArrayList<>();
        boolean falharPublicacao;

        @Override
        public void publicarResultado(UUID commandId, String correlationId, ResultadoSincronizacao resultado) {
            if (falharPublicacao) throw new FalhaDePublicacaoException("sem confirm");
            resultados.add(resultado);
        }

        @Override
        public void reenfileirar(ComandoRecebido comando, Destino destino, int tentativa) {
            reenfileirados.add(new Reenvio(destino, tentativa));
        }

        @Override
        public void enviarIlegivelParaDlq(byte[] corpo, String messageId, String correlationId, int tentativa, String motivo) {
            ilegiveis.add(motivo);
        }
    }

    static class RepositorioFake implements ComandoRepositorio {
        final Map<UUID, ComandoProcessado> itens = new LinkedHashMap<>();

        @Override
        public Optional<ComandoProcessado> buscar(UUID id) {
            return Optional.ofNullable(itens.get(id));
        }

        @Override
        public boolean salvar(UUID id, ResultadoSincronizacao r) {
            return itens.putIfAbsent(id, new ComandoProcessado(id, r.turmaId(), r.versao(), r.status(), r, Instant.now())) == null;
        }

        @Override
        public int maiorVersaoProcessada(long turmaId) {
            return itens.values().stream().filter(c -> c.turmaId() == turmaId).mapToInt(ComandoProcessado::turmaVersao).max().orElse(0);
        }

        @Override
        public List<ComandoProcessado> ultimos(int n) {
            return new ArrayList<>(itens.values());
        }
    }
}
