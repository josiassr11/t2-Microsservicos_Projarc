package com.pucrs.publicadormoodle.domain.port.out;

/** Para onde reenviar um comando que não pôde ser concluído agora. */
public enum Destino {
    RETRY_5S("retry-5s"), RETRY_30S("retry-30s"), DLQ("dlq");

    private final String rotulo;

    Destino(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
