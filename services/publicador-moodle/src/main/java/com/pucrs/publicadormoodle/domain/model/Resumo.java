package com.pucrs.publicadormoodle.domain.model;

public record Resumo(int cursosCriados, int cursosAtualizados, int cursosOcultados, int usuariosCriados,
                     int usuariosAtualizados, int inscricoesCriadas, int inscricoesSuspensas) {

    public static Resumo vazio() {
        return new Resumo(0, 0, 0, 0, 0, 0, 0);
    }
}
