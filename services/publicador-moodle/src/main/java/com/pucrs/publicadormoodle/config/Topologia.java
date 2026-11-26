package com.pucrs.publicadormoodle.config;

import com.pucrs.publicadormoodle.domain.port.out.Destino;

/** Nomes de exchanges, filas e routing keys deste serviço (contrato em docs/contratos.md). */
public final class Topologia {

    public static final String EXCHANGE_COMANDOS = "sincronizacao.comandos";
    public static final String RK_COMANDO = "sincronizar.turma";
    public static final String EXCHANGE_RESULTADOS = "moodle.eventos";
    public static final String RK_RESULTADO = "turma.sincronizada";

    public static final String FILA_COMANDOS = "publicador-moodle.comandos";
    public static final String FILA_RETRY_5S = "publicador-moodle.comandos.retry-5s";
    public static final String FILA_RETRY_30S = "publicador-moodle.comandos.retry-30s";
    public static final String FILA_DLQ = "publicador-moodle.comandos.dlq";

    public static final String HEADER_TENTATIVA = "x-tentativa";

    private Topologia() {
    }

    public static String filaDe(Destino destino) {
        return switch (destino) {
            case RETRY_5S -> FILA_RETRY_5S;
            case RETRY_30S -> FILA_RETRY_30S;
            case DLQ -> FILA_DLQ;
        };
    }
}
