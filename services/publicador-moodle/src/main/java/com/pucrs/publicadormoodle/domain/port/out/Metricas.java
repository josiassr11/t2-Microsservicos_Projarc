package com.pucrs.publicadormoodle.domain.port.out;

/** Contadores de negócio (viram métricas Prometheus). */
public interface Metricas {

    /** resultado: sucesso, parcial, falha, duplicado, obsoleto, retry, dlq, ilegivel. */
    void comando(String resultado);
}
