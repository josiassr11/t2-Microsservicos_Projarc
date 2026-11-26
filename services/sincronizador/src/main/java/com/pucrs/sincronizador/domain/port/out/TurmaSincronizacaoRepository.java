package com.pucrs.sincronizador.domain.port.out;

import com.pucrs.sincronizador.domain.model.EstadoSincronizacao;
import com.pucrs.sincronizador.domain.model.Resumo;
import com.pucrs.sincronizador.domain.model.TurmaInfo;
import com.pucrs.sincronizador.domain.model.TurmaSincronizacao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TurmaSincronizacaoRepository {

    /** Cria/atualiza a turma com estado ENVIADA e versão + 1. @return a nova versão. */
    int registrarEnvio(TurmaInfo turma, String hash, UUID eventId);

    /**
     * Grava o resultado se {@code versao} >= versão atual da turma.
     *
     * @return {@code false} quando o resultado é obsoleto (nada foi alterado)
     */
    boolean registrarResultado(long turmaId, int versao, EstadoSincronizacao estado, Long categoriaMoodleId,
                               Long cursoMoodleId, Resumo resumo, String erro, UUID eventId);

    Optional<TurmaSincronizacao> buscar(long turmaId);

    List<TurmaSincronizacao> listar();
}
