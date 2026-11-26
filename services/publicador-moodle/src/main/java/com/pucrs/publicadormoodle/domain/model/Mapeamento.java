package com.pucrs.publicadormoodle.domain.model;

/** Vínculo iEducar → Moodle devolvido ao sincronizador (que é o dono do mapeamento). */
public record Mapeamento(Tipo tipo, long ieducarId, long moodleId, String idnumber) {

    public enum Tipo {CATEGORIA, CURSO, ALUNO, PROFESSOR}
}
