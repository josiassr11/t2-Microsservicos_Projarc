package com.pucrs.publicadormoodle.application;

import com.pucrs.publicadormoodle.domain.model.TurmaComando;

import java.util.UUID;

/**
 * Comando {@code SincronizarTurma.v1} já lido e validado pelo adaptador de entrada. Guarda o corpo original para
 * poder reenfileirar a mensagem exatamente como chegou.
 */
public record ComandoRecebido(UUID commandId, String correlationId, TurmaComando comando, byte[] corpoOriginal) {

    public long turmaId() {
        return comando.turma().id();
    }
}
