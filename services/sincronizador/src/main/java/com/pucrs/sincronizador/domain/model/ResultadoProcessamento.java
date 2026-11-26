package com.pucrs.sincronizador.domain.model;

public enum ResultadoProcessamento {
    /** Mensagem aplicada. */
    PROCESSADA,
    /** eventId já tinha sido processado: descartada sem efeito. */
    DUPLICADA,
    /** Resultado de uma versão anterior à atual da turma: só registrado. */
    OBSOLETA
}
