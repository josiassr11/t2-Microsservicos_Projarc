package com.pucrs.sincronizador.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Estado atual da integração de uma turma. */
public record TurmaSincronizacao(long turmaId, Long cursoIeducarId, String nome, int versao,
                                 EstadoSincronizacao estado, String hash, UUID ultimoEventoId,
                                 Long cursoMoodleId, Long categoriaMoodleId, Resumo ultimoResumo,
                                 String ultimoErro, Instant atualizadoEm) {
}
