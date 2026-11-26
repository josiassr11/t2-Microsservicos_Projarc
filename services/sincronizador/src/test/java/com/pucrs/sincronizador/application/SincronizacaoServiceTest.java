package com.pucrs.sincronizador.application;

import com.pucrs.sincronizador.domain.exception.PublicacaoException;
import com.pucrs.sincronizador.domain.model.ComandoSincronizar;
import com.pucrs.sincronizador.domain.model.DadosTurma;
import com.pucrs.sincronizador.domain.model.EstadoSincronizacao;
import com.pucrs.sincronizador.domain.model.EventoTurmaAlterada;
import com.pucrs.sincronizador.domain.model.IdsDeterministicos;
import com.pucrs.sincronizador.domain.model.MapeamentoItem;
import com.pucrs.sincronizador.domain.model.Participante;
import com.pucrs.sincronizador.domain.model.Resumo;
import com.pucrs.sincronizador.domain.model.ResultadoProcessamento;
import com.pucrs.sincronizador.domain.model.ResultadoSincronizacao;
import com.pucrs.sincronizador.domain.model.TurmaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SincronizacaoServiceTest {

    FakeMundo mundo;
    SincronizacaoService service;

    @BeforeEach
    void preparar() {
        mundo = new FakeMundo();
        service = new SincronizacaoService(mundo, mundo, mundo, mundo.mapeamentoRepo, mundo);
    }

    static EventoTurmaAlterada evento(UUID id, long turmaId) {
        TurmaInfo turma = new TurmaInfo(turmaId, "1º Ano A", 2026, true, 1, "Escola X", 3, "Ensino Fundamental");
        DadosTurma dados = new DadosTurma("hash-" + id, turma,
                List.of(new Participante(7, "Ana Silva", null, true)),
                List.of(new Participante(2, "Carlos Souza", "c@x.com", true)));
        return new EventoTurmaAlterada(id, "corr-1", "CRIADA", dados);
    }

    static ResultadoSincronizacao resultado(UUID id, long turmaId, int versao, String status) {
        return new ResultadoSincronizacao(id, "corr-1", turmaId, versao, status, 4L, 12L,
                new Resumo(1, 0, 0, 2, 0, 2, 0),
                List.of(new MapeamentoItem("CATEGORIA", 3, 4, "IEDUCAR-CURSO-3"),
                        new MapeamentoItem("CURSO", turmaId, 12, "IEDUCAR-TURMA-" + turmaId),
                        new MapeamentoItem("ALUNO", 7, 30, "IEDUCAR-ALUNO-7")),
                "SUCESSO".equals(status) ? List.of() : List.of("aluno 9: e-mail recusado"), 1);
    }

    @Test
    void eventoNovo_geraComandoEEstadoEnviada() {
        UUID id = UUID.randomUUID();

        ResultadoProcessamento r = service.processarTurmaAlterada(evento(id, 10));

        assertThat(r).isEqualTo(ResultadoProcessamento.PROCESSADA);
        assertThat(mundo.comandosPublicados).hasSize(1);
        ComandoSincronizar c = mundo.comandosPublicados.get(0);
        assertThat(c.versao()).isEqualTo(1);
        assertThat(c.correlationId()).isEqualTo("corr-1");
        assertThat(c.dados().turma().id()).isEqualTo(10);
        assertThat(c.commandId()).isEqualTo(IdsDeterministicos.commandId(id));
        assertThat(mundo.buscar(10).orElseThrow().estado()).isEqualTo(EstadoSincronizacao.ENVIADA);
        assertThat(mundo.buscar(10).orElseThrow().versao()).isEqualTo(1);
    }

    @Test
    void duplicata_naoGeraSegundoComando() {
        UUID id = UUID.randomUUID();
        service.processarTurmaAlterada(evento(id, 10));

        ResultadoProcessamento r = service.processarTurmaAlterada(evento(id, 10));

        assertThat(r).isEqualTo(ResultadoProcessamento.DUPLICADA);
        assertThat(mundo.comandosPublicados).hasSize(1);
        assertThat(mundo.buscar(10).orElseThrow().versao()).isEqualTo(1);
    }

    @Test
    void falhaAoPublicar_naoMarcaEventoComoProcessado_ePodeSerReprocessado() {
        UUID id = UUID.randomUUID();
        mundo.falhasDePublicacaoRestantes = 1;

        assertThatThrownBy(() -> service.processarTurmaAlterada(evento(id, 10)))
                .isInstanceOf(PublicacaoException.class);

        assertThat(mundo.eventos).doesNotContain(id);
        assertThat(mundo.buscar(10)).isEmpty();
        assertThat(mundo.comandosPublicados).isEmpty();

        // reentrega da mesma mensagem: agora funciona e gera o MESMO commandId
        assertThat(service.processarTurmaAlterada(evento(id, 10))).isEqualTo(ResultadoProcessamento.PROCESSADA);
        assertThat(mundo.comandosPublicados).hasSize(1);
        assertThat(mundo.comandosPublicados.get(0).commandId()).isEqualTo(IdsDeterministicos.commandId(id));
    }

    @Test
    void segundoEventoDaMesmaTurma_incrementaAVersao() {
        service.processarTurmaAlterada(evento(UUID.randomUUID(), 10));
        service.processarTurmaAlterada(evento(UUID.randomUUID(), 10));

        assertThat(mundo.comandosPublicados).extracting(ComandoSincronizar::versao).containsExactly(1, 2);
        assertThat(mundo.buscar(10).orElseThrow().versao()).isEqualTo(2);
    }

    @Test
    void resultado_sucesso_atualizaEstadoEMapeamentos() {
        service.processarTurmaAlterada(evento(UUID.randomUUID(), 10));

        ResultadoProcessamento r = service.processarResultado(resultado(UUID.randomUUID(), 10, 1, "SUCESSO"));

        assertThat(r).isEqualTo(ResultadoProcessamento.PROCESSADA);
        var turma = mundo.buscar(10).orElseThrow();
        assertThat(turma.estado()).isEqualTo(EstadoSincronizacao.SINCRONIZADA);
        assertThat(turma.cursoMoodleId()).isEqualTo(12);
        assertThat(turma.categoriaMoodleId()).isEqualTo(4);
        assertThat(turma.ultimoResumo().inscricoesCriadas()).isEqualTo(2);
        assertThat(mundo.mapeamentoRepo.listar()).extracting(MapeamentoItem::idnumber)
                .containsExactlyInAnyOrder("IEDUCAR-CURSO-3", "IEDUCAR-TURMA-10", "IEDUCAR-ALUNO-7");
    }

    @Test
    void resultado_parcial_guardaOsErros() {
        service.processarTurmaAlterada(evento(UUID.randomUUID(), 10));

        service.processarResultado(resultado(UUID.randomUUID(), 10, 1, "PARCIAL"));

        var turma = mundo.buscar(10).orElseThrow();
        assertThat(turma.estado()).isEqualTo(EstadoSincronizacao.PARCIAL);
        assertThat(turma.ultimoErro()).contains("e-mail recusado");
    }

    @Test
    void resultado_falha_marcaFalha() {
        service.processarTurmaAlterada(evento(UUID.randomUUID(), 10));

        service.processarResultado(resultado(UUID.randomUUID(), 10, 1, "FALHA"));

        assertThat(mundo.buscar(10).orElseThrow().estado()).isEqualTo(EstadoSincronizacao.FALHA);
    }

    @Test
    void resultadoObsoleto_naoRegrideOEstado() {
        service.processarTurmaAlterada(evento(UUID.randomUUID(), 10));   // v1
        service.processarTurmaAlterada(evento(UUID.randomUUID(), 10));   // v2

        ResultadoProcessamento r = service.processarResultado(resultado(UUID.randomUUID(), 10, 1, "SUCESSO"));

        assertThat(r).isEqualTo(ResultadoProcessamento.OBSOLETA);
        var turma = mundo.buscar(10).orElseThrow();
        assertThat(turma.estado()).isEqualTo(EstadoSincronizacao.ENVIADA);
        assertThat(turma.versao()).isEqualTo(2);
        assertThat(mundo.mapeamentoRepo.listar()).isEmpty();
    }

    @Test
    void resultadoDuplicado_eIgnorado() {
        service.processarTurmaAlterada(evento(UUID.randomUUID(), 10));
        UUID idResultado = UUID.randomUUID();
        service.processarResultado(resultado(idResultado, 10, 1, "SUCESSO"));

        ResultadoProcessamento r = service.processarResultado(resultado(idResultado, 10, 1, "FALHA"));

        assertThat(r).isEqualTo(ResultadoProcessamento.DUPLICADA);
        assertThat(mundo.buscar(10).orElseThrow().estado()).isEqualTo(EstadoSincronizacao.SINCRONIZADA);
    }

    @Test
    void commandId_eDeterministico() {
        UUID id = UUID.randomUUID();
        assertThat(IdsDeterministicos.commandId(id)).isEqualTo(IdsDeterministicos.commandId(id));
        assertThat(IdsDeterministicos.commandId(id)).isNotEqualTo(IdsDeterministicos.commandId(UUID.randomUUID()));
    }
}
