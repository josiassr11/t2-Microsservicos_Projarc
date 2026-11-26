package com.pucrs.sincronizador.application;

import com.pucrs.sincronizador.domain.exception.PublicacaoException;
import com.pucrs.sincronizador.domain.model.ComandoSincronizar;
import com.pucrs.sincronizador.domain.model.EstadoSincronizacao;
import com.pucrs.sincronizador.domain.model.MapeamentoItem;
import com.pucrs.sincronizador.domain.model.Resumo;
import com.pucrs.sincronizador.domain.model.TurmaInfo;
import com.pucrs.sincronizador.domain.model.TurmaSincronizacao;
import com.pucrs.sincronizador.domain.port.out.ComandoPublisher;
import com.pucrs.sincronizador.domain.port.out.EventoProcessadoRepository;
import com.pucrs.sincronizador.domain.port.out.MapeamentoRepository;
import com.pucrs.sincronizador.domain.port.out.Transacao;
import com.pucrs.sincronizador.domain.port.out.TurmaSincronizacaoRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Banco + broker em memória. A "transação" restaura o estado anterior se o bloco lançar exceção (como o Postgres). */
class FakeMundo implements Transacao, EventoProcessadoRepository, TurmaSincronizacaoRepository,
        ComandoPublisher {

    Set<UUID> eventos = new HashSet<>();
    Map<Long, TurmaSincronizacao> turmas = new HashMap<>();
    Map<String, MapeamentoItem> mapeamentos = new HashMap<>();
    final List<ComandoSincronizar> comandosPublicados = new ArrayList<>();
    int falhasDePublicacaoRestantes;

    @Override
    public <T> T executar(Supplier<T> bloco) {
        Set<UUID> e = new HashSet<>(eventos);
        Map<Long, TurmaSincronizacao> t = new HashMap<>(turmas);
        Map<String, MapeamentoItem> m = new HashMap<>(mapeamentos);
        try {
            return bloco.get();
        } catch (RuntimeException ex) {
            eventos = e;
            turmas = t;
            mapeamentos = m;
            throw ex;
        }
    }

    @Override
    public boolean registrar(UUID eventId, String tipo, String correlationId) {
        return eventos.add(eventId);
    }

    @Override
    public int registrarEnvio(TurmaInfo turma, String hash, UUID eventId) {
        int versao = Optional.ofNullable(turmas.get(turma.id())).map(x -> x.versao() + 1).orElse(1);
        turmas.put(turma.id(), new TurmaSincronizacao(turma.id(), turma.cursoId(), turma.nome(), versao,
                EstadoSincronizacao.ENVIADA, hash, eventId, null, null, null, null, Instant.now()));
        return versao;
    }

    @Override
    public boolean registrarResultado(long turmaId, int versao, EstadoSincronizacao estado, Long categoriaMoodleId,
                                      Long cursoMoodleId, Resumo resumo, String erro, UUID eventId) {
        TurmaSincronizacao atual = turmas.get(turmaId);
        if (atual != null && atual.versao() > versao) {
            return false;
        }
        turmas.put(turmaId, new TurmaSincronizacao(turmaId, atual == null ? null : atual.cursoIeducarId(),
                atual == null ? null : atual.nome(), versao, estado, atual == null ? null : atual.hash(),
                atual == null ? null : atual.ultimoEventoId(), cursoMoodleId, categoriaMoodleId, resumo, erro,
                Instant.now()));
        return true;
    }

    @Override
    public Optional<TurmaSincronizacao> buscar(long turmaId) {
        return Optional.ofNullable(turmas.get(turmaId));
    }

    @Override
    public List<TurmaSincronizacao> listar() {
        return new ArrayList<>(turmas.values());
    }

    /** Repositório de mapeamentos (classe à parte porque {@code listar()} colide com o das turmas). */
    final MapeamentoRepository mapeamentoRepo = new MapeamentoRepository() {
        @Override
        public void salvar(Collection<MapeamentoItem> itens) {
            itens.forEach(i -> mapeamentos.put(i.tipo() + ":" + i.ieducarId(), i));
        }

        @Override
        public List<MapeamentoItem> listar() {
            return new ArrayList<>(mapeamentos.values());
        }
    };

    @Override
    public void publicar(ComandoSincronizar comando) {
        if (falhasDePublicacaoRestantes > 0) {
            falhasDePublicacaoRestantes--;
            throw new PublicacaoException("broker fora do ar (simulado)");
        }
        comandosPublicados.add(comando);
    }
}
