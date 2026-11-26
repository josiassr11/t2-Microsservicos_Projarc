package com.pucrs.publicadormoodle.domain.model;

import java.util.List;

/** Conteúdo do resultado {@code TurmaSincronizada.v1}. */
public record ResultadoSincronizacao(long turmaId, int versao, StatusSincronizacao status, Long categoriaMoodleId,
                                     Long cursoMoodleId, Resumo resumo, List<Mapeamento> mapeamentos,
                                     List<String> erros, int tentativas) {

    public ResultadoSincronizacao {
        mapeamentos = mapeamentos == null ? List.of() : List.copyOf(mapeamentos);
        erros = erros == null ? List.of() : List.copyOf(erros);
        resumo = resumo == null ? Resumo.vazio() : resumo;
    }

    public ResultadoSincronizacao comTentativas(int n) {
        return new ResultadoSincronizacao(turmaId, versao, status, categoriaMoodleId, cursoMoodleId, resumo,
                mapeamentos, erros, n);
    }

    public static ResultadoSincronizacao falha(TurmaComando c, int tentativas, String motivo) {
        return new ResultadoSincronizacao(c.turma().id(), c.versao(), StatusSincronizacao.FALHA, null, null,
                Resumo.vazio(), List.of(), List.of(motivo), tentativas);
    }
}
