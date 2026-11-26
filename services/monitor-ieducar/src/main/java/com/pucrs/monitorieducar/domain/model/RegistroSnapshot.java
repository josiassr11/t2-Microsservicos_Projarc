package com.pucrs.monitorieducar.domain.model;

import java.time.OffsetDateTime;

/** Linha da tabela {@code turma_snapshot}: o que o monitor já publicou de cada turma. */
public record RegistroSnapshot(long turmaId, String hash, String nome, boolean ativa, int versaoSnapshot,
                               OffsetDateTime ultimaPublicacao) {
}
