package com.pucrs.monitorieducar.domain.model;

import java.time.Instant;

/** Resultado de uma varredura (devolvido pela API e gravado na tabela {@code varredura}). */
public record ResultadoVarredura(Long id, String correlationId, TipoVarredura tipo, StatusVarredura status,
                                 Instant inicio, Instant fim, int turmasLidas, int eventosPublicados,
                                 int turmasInalteradas, int falhasPublicacao, String erro) {
}
