package com.pucrs.monitorieducar.application;

import com.pucrs.monitorieducar.application.Fakes.IEducarFake;
import com.pucrs.monitorieducar.application.Fakes.MetricasFake;
import com.pucrs.monitorieducar.application.Fakes.PublicadorFake;
import com.pucrs.monitorieducar.application.Fakes.SnapshotRepoFake;
import com.pucrs.monitorieducar.application.Fakes.VarreduraRepoFake;
import com.pucrs.monitorieducar.domain.exception.VarreduraEmAndamentoException;
import com.pucrs.monitorieducar.domain.model.Curso;
import com.pucrs.monitorieducar.domain.model.EventoTurmaAlterada;
import com.pucrs.monitorieducar.domain.model.Matricula;
import com.pucrs.monitorieducar.domain.model.ResultadoVarredura;
import com.pucrs.monitorieducar.domain.model.StatusVarredura;
import com.pucrs.monitorieducar.domain.model.TipoMudanca;
import com.pucrs.monitorieducar.domain.model.TipoVarredura;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.VinculoProfessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarreduraServiceTest {

    IEducarFake ieducar;
    PublicadorFake publicador;
    SnapshotRepoFake snapshots;
    VarreduraRepoFake varreduras;
    MetricasFake metricas;
    VarreduraService service;

    @BeforeEach
    void preparar() {
        ieducar = new IEducarFake();
        publicador = new PublicadorFake();
        snapshots = new SnapshotRepoFake();
        varreduras = new VarreduraRepoFake();
        metricas = new MetricasFake();
        service = new VarreduraService(ieducar, publicador, snapshots, varreduras, metricas);

        Curso curso = new Curso(3, "Fundamental", true);
        ieducar.turmas.add(new Turma(10, "1º Ano A", 2026, true, 1, "Escola X", curso));
        ieducar.turmas.add(new Turma(11, "1º Ano B", 2026, true, 1, "Escola X", curso));
        ieducar.matriculas.add(new Matricula(ieducar.aluno(7, "Ana Silva"), 10, true));
        ieducar.vinculos.add(new VinculoProfessor(ieducar.professor(2, "Carlos Souza"), 10, true));
    }

    @Test
    void primeiraVarreduraPublicaTodasComoCriada() {
        ResultadoVarredura r = service.varrer(TipoVarredura.INCREMENTAL);

        assertThat(r.status()).isEqualTo(StatusVarredura.SUCESSO);
        assertThat(r.turmasLidas()).isEqualTo(2);
        assertThat(r.eventosPublicados()).isEqualTo(2);
        assertThat(publicador.publicados).extracting(EventoTurmaAlterada::mudanca)
                .containsOnly(TipoMudanca.CRIADA);
        EventoTurmaAlterada evento = publicador.publicados.get(0);
        assertThat(evento.snapshot().alunos()).extracting(p -> p.nome()).containsExactly("Ana Silva");
        assertThat(evento.snapshot().professores()).extracting(p -> p.nome()).containsExactly("Carlos Souza");
        assertThat(evento.correlationId().toString()).isEqualTo(r.correlationId());
        assertThat(snapshots.hashes).containsOnlyKeys(10L, 11L);
        assertThat(metricas.status).containsExactly(StatusVarredura.SUCESSO);
    }

    @Test
    void segundaVarreduraSemMudancaNaoPublicaNada() {
        service.varrer(TipoVarredura.INCREMENTAL);
        publicador.publicados.clear();

        ResultadoVarredura r = service.varrer(TipoVarredura.INCREMENTAL);

        assertThat(publicador.publicados).isEmpty();
        assertThat(r.eventosPublicados()).isZero();
        assertThat(r.turmasInalteradas()).isEqualTo(2);
        assertThat(r.status()).isEqualTo(StatusVarredura.SUCESSO);
    }

    @Test
    void publicaSoAsTurmasQueMudaram() {
        service.varrer(TipoVarredura.INCREMENTAL);
        publicador.publicados.clear();
        // novo aluno na turma 10; a 11 não muda
        ieducar.matriculas.add(new Matricula(ieducar.aluno(8, "Bia Lima"), 10, true));

        service.varrer(TipoVarredura.INCREMENTAL);

        assertThat(publicador.publicados).hasSize(1);
        assertThat(publicador.publicados.get(0).snapshot().turma().id()).isEqualTo(10);
        assertThat(publicador.publicados.get(0).mudanca()).isEqualTo(TipoMudanca.ATUALIZADA);
        assertThat(publicador.publicados.get(0).snapshot().alunos()).hasSize(2);
    }

    @Test
    void enturmacaoCanceladaViraEventoComAlunoInativo() {
        service.varrer(TipoVarredura.INCREMENTAL);
        publicador.publicados.clear();
        ieducar.matriculas.set(0, new Matricula(ieducar.aluno(7, "Ana Silva"), 10, false));

        service.varrer(TipoVarredura.INCREMENTAL);

        assertThat(publicador.publicados).hasSize(1);
        assertThat(publicador.publicados.get(0).snapshot().alunos().get(0).ativo()).isFalse();
    }

    @Test
    void varreduraCompletaRepublicaTodasComoRepublicada() {
        service.varrer(TipoVarredura.INCREMENTAL);
        publicador.publicados.clear();

        service.varrer(TipoVarredura.COMPLETA);

        assertThat(publicador.publicados).hasSize(2);
        assertThat(publicador.publicados).extracting(EventoTurmaAlterada::mudanca).containsOnly(TipoMudanca.REPUBLICADA);
    }

    @Test
    void falhaNaPublicacaoNaoAtualizaOSnapshot_eAProximaVarreduraRepublica() {
        publicador.falhar.add(10L);

        ResultadoVarredura r = service.varrer(TipoVarredura.INCREMENTAL);

        assertThat(r.status()).isEqualTo(StatusVarredura.PARCIAL);
        assertThat(r.falhasPublicacao()).isEqualTo(1);
        assertThat(r.eventosPublicados()).isEqualTo(1);
        assertThat(snapshots.hashes).containsOnlyKeys(11L);   // a 10 NÃO avançou
        assertThat(publicador.publicados).extracting(e -> e.snapshot().turma().id()).containsExactly(11L);

        publicador.falhar.clear();                              // o broker voltou
        ResultadoVarredura segunda = service.varrer(TipoVarredura.INCREMENTAL);

        assertThat(segunda.status()).isEqualTo(StatusVarredura.SUCESSO);
        assertThat(publicador.publicados).extracting(e -> e.snapshot().turma().id()).containsExactly(11L, 10L);
        assertThat(publicador.publicados.get(1).mudanca()).isEqualTo(TipoMudanca.CRIADA);
        assertThat(snapshots.hashes).containsOnlyKeys(10L, 11L);
    }

    @Test
    void ieducarForaDoArAbortaSemPublicarNada() {
        ieducar.foraDoAr = true;

        ResultadoVarredura r = service.varrer(TipoVarredura.INCREMENTAL);

        assertThat(r.status()).isEqualTo(StatusVarredura.FALHA);
        assertThat(r.erro()).contains("iEducar indisponível");
        assertThat(publicador.publicados).isEmpty();
        assertThat(snapshots.hashes).isEmpty();
        assertThat(metricas.status).containsExactly(StatusVarredura.FALHA);

        ieducar.foraDoAr = false;                               // voltou
        assertThat(service.varrer(TipoVarredura.INCREMENTAL).status()).isEqualTo(StatusVarredura.SUCESSO);
        assertThat(publicador.publicados).hasSize(2);
    }

    @Test
    void turmaInativaVaiComListasVazias_eMudarParaInativaGeraEvento() {
        service.varrer(TipoVarredura.INCREMENTAL);
        publicador.publicados.clear();
        Turma antiga = ieducar.turmas.get(0);
        ieducar.turmas.set(0, new Turma(antiga.id(), antiga.nome(), antiga.ano(), false, antiga.escolaId(),
                antiga.escolaNome(), antiga.curso()));

        service.varrer(TipoVarredura.INCREMENTAL);

        assertThat(publicador.publicados).hasSize(1);
        EventoTurmaAlterada e = publicador.publicados.get(0);
        assertThat(e.snapshot().turma().ativa()).isFalse();
        assertThat(e.snapshot().alunos()).isEmpty();
    }

    @Test
    void duasVarredurasAoMesmoTempo_aSegundaRecebe409() throws Exception {
        ieducar.segurar = new CountDownLatch(1);
        CompletableFuture<ResultadoVarredura> primeira =
                CompletableFuture.supplyAsync(() -> service.varrer(TipoVarredura.INCREMENTAL));
        assertThat(ieducar.entrou.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.varrer(TipoVarredura.INCREMENTAL))
                .isInstanceOf(VarreduraEmAndamentoException.class);

        ieducar.segurar.countDown();
        assertThat(primeira.get(5, TimeUnit.SECONDS).status()).isEqualTo(StatusVarredura.SUCESSO);
    }
}
