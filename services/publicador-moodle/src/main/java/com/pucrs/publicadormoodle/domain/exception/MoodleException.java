package com.pucrs.publicadormoodle.domain.exception;

/** O Moodle respondeu, mas recusou a operação (falha de <b>negócio</b>: não adianta repetir a mesma chamada). */
public class MoodleException extends SistemaExternoException {

    private final String errorCode;

    public MoodleException(String errorCode, String mensagem) {
        super("Moodle", "Moodle recusou a operação [" + errorCode + "]: " + mensagem);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
