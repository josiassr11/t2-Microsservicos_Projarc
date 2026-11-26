package com.pucrs.sincronizador.domain.model;

import java.time.Instant;

/** Ligação entre um objeto do iEducar e o objeto correspondente no Moodle. */
public record MapeamentoItem(String tipo, long ieducarId, long moodleId, String idnumber, Instant atualizadoEm) {

    public MapeamentoItem(String tipo, long ieducarId, long moodleId, String idnumber) {
        this(tipo, ieducarId, moodleId, idnumber, null);
    }
}
