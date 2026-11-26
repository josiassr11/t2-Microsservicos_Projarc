package com.pucrs.monitorieducar.application;

import com.pucrs.monitorieducar.domain.exception.PublicacaoFalhouException;
import com.pucrs.monitorieducar.domain.exception.SistemaIndisponivelException;
import com.pucrs.monitorieducar.domain.model.Aluno;
import com.pucrs.monitorieducar.domain.model.EventoTurmaAlterada;
import com.pucrs.monitorieducar.domain.model.Matricula;
import com.pucrs.monitorieducar.domain.model.Professor;
import com.pucrs.monitorieducar.domain.model.RegistroSnapshot;
import com.pucrs.monitorieducar.domain.model.ResultadoVarredura;
import com.pucrs.monitorieducar.domain.model.StatusVarredura;
import com.pucrs.monitorieducar.domain.model.TipoVarredura;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.VinculoProfessor;
import com.pucrs.monitorieducar.domain.port.out.IEducarPort;
import com.pucrs.monitorieducar.domain.port.out.MetricasPort;
import com.pucrs.monitorieducar.domain.port.out.PublicadorEventosPort;
import com.pucrs.monitorieducar.domain.port.out.SnapshotRepositoryPort;
import com.pucrs.monitorieducar.domain.port.out.VarreduraRepositoryPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

/** Implementações em memória das portas, para testar o VarreduraService sem Docker. */
final class Fakes {

    private Fakes() {
    }

    static class IEducarFake implements IEducarPort {
        List<Turma> turmas = new ArrayList<>();
        List<Matricula> matriculas = new ArrayList<>();
        List<VinculoProfessor> vinculos = new ArrayList<>();
        boolean foraDoAr;
        CountDownLatch segurar;       // bloqueia listarTurmas até liberar (teste de concorrência)
        CountDownLatch entrou = new CountDownLatch(1);

        @Override
        public List<Turma> listarTurmas() {
            entrou.countDown();
            if (segurar != null) {
                try {
                    segurar.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (foraDoAr) {
                throw new SistemaIndisponivelException("iEducar", "connection refused");
            }
            return List.copyOf(turmas);
        }

        @Override
        public List<Matricula> listarMatriculas(int ano) {
            return List.copyOf(matriculas);
        }

        @Override
        public List<VinculoProfessor> listarVinculosProfessores(int ano, Collection<Long> escolaIds) {
            return List.copyOf(vinculos);
        }

        Aluno aluno(long id, String nome) {
            return new Aluno(id, nome, null, null);
        }

        Professor professor(long id, String nome) {
            return new Professor(id, nome, null, null);
        }
    }

    static class PublicadorFake implements PublicadorEventosPort {
        final List<EventoTurmaAlterada> publicados = new ArrayList<>();
        final Set<Long> falhar = new HashSet<>();

        @Override
        public void publicar(EventoTurmaAlterada evento) {
            if (falhar.contains(evento.snapshot().turma().id())) {
                throw new PublicacaoFalhouException("RabbitMQ indisponível");
            }
            publicados.add(evento);
        }
    }

    static class SnapshotRepoFake implements SnapshotRepositoryPort {
        final Map<Long, String> hashes = new TreeMap<>();

        @Override
        public Optional<String> buscarHash(long turmaId) {
            return Optional.ofNullable(hashes.get(turmaId));
        }

        @Override
        public void salvar(long turmaId, String hash, String nome, boolean ativa) {
            hashes.put(turmaId, hash);
        }

        @Override
        public List<RegistroSnapshot> listar() {
            return List.of();
        }
    }

    static class VarreduraRepoFake implements VarreduraRepositoryPort {
        final List<ResultadoVarredura> concluidas = new ArrayList<>();

        @Override
        public long iniciar(String correlationId, TipoVarredura tipo, Instant inicio) {
            return concluidas.size() + 1;
        }

        @Override
        public void concluir(long id, ResultadoVarredura resultado) {
            concluidas.add(resultado);
        }

        @Override
        public Optional<ResultadoVarredura> ultima() {
            return concluidas.isEmpty() ? Optional.empty() : Optional.of(concluidas.get(concluidas.size() - 1));
        }
    }

    static class MetricasFake implements MetricasPort {
        final List<StatusVarredura> status = new ArrayList<>();

        @Override
        public void varreduraConcluida(StatusVarredura s) {
            status.add(s);
        }
    }
}
