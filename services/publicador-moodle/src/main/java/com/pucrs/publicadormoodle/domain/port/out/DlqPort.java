package com.pucrs.publicadormoodle.domain.port.out;

public interface DlqPort {

    int quantidade();

    /** Move todas as mensagens da DLQ de volta à fila principal (x-tentativa=1). Devolve quantas moveu. */
    int reprocessar();
}
