package com.pucrs.publicadormoodle.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Linha da inbox: comando concluído + resultado que foi (ou será reemitido) ao sincronizador. */
public record ComandoProcessado(UUID commandId, long turmaId, int turmaVersao, StatusSincronizacao status,
                                ResultadoSincronizacao resultado, Instant processadoEm) {
}
