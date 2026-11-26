package com.pucrs.publicadormoodle.domain.port.out;

import com.pucrs.publicadormoodle.domain.model.ComandoProcessado;
import com.pucrs.publicadormoodle.domain.model.ResultadoSincronizacao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Inbox do serviço (banco próprio do publicador-moodle). */
public interface ComandoRepositorio {

    Optional<ComandoProcessado> buscar(UUID commandId);

    /** Grava o comando concluído; devolve false se já existia (duplicata concorrente). */
    boolean salvar(UUID commandId, ResultadoSincronizacao resultado);

    /** Maior versão já concluída da turma (0 se nenhuma). */
    int maiorVersaoProcessada(long turmaId);

    List<ComandoProcessado> ultimos(int quantidade);
}
