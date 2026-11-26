package com.pucrs.sincronizador.domain.model;

import java.util.List;
import java.util.UUID;

/** {@code TurmaSincronizada.v1} já interpretado. */
public record ResultadoSincronizacao(UUID eventId, String correlationId, long turmaId, int versao, String status,
                                     Long categoriaMoodleId, Long cursoMoodleId, Resumo resumo,
                                     List<MapeamentoItem> mapeamentos, List<String> erros, int tentativas) {

    public ResultadoSincronizacao {
        resumo = resumo == null ? Resumo.vazio() : resumo;
        mapeamentos = mapeamentos == null ? List.of() : List.copyOf(mapeamentos);
        erros = erros == null ? List.of() : List.copyOf(erros);
    }

    public EstadoSincronizacao estado() {
        return switch (status == null ? "" : status) {
            case "SUCESSO" -> EstadoSincronizacao.SINCRONIZADA;
            case "PARCIAL" -> EstadoSincronizacao.PARCIAL;
            default -> EstadoSincronizacao.FALHA;
        };
    }
}
