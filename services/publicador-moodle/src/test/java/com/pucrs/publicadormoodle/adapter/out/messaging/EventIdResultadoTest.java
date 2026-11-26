package com.pucrs.publicadormoodle.adapter.out.messaging;

import com.pucrs.publicadormoodle.domain.model.StatusSincronizacao;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** O resultado que sucede uma FALHA (reprocesso da DLQ) não pode ser confundido com duplicata pelo sincronizador. */
class EventIdResultadoTest {

    private final UUID comando = UUID.fromString("1f678733-705d-3fde-9d81-86d4adb94f50");

    @Test
    void sucessoEhDeterministicoParaOMesmoComando() {
        assertEquals(RabbitMensageria.eventIdDoResultado(comando, StatusSincronizacao.SUCESSO),
                RabbitMensageria.eventIdDoResultado(comando, StatusSincronizacao.SUCESSO));
    }

    @Test
    void statusDiferentesGeramIdsDiferentes() {
        assertNotEquals(RabbitMensageria.eventIdDoResultado(comando, StatusSincronizacao.SUCESSO),
                RabbitMensageria.eventIdDoResultado(comando, StatusSincronizacao.PARCIAL));
    }

    @Test
    void falhaGeraSempreUmIdNovo() {
        assertNotEquals(RabbitMensageria.eventIdDoResultado(comando, StatusSincronizacao.FALHA),
                RabbitMensageria.eventIdDoResultado(comando, StatusSincronizacao.FALHA));
        assertNotEquals(RabbitMensageria.eventIdDoResultado(comando, StatusSincronizacao.FALHA),
                RabbitMensageria.eventIdDoResultado(comando, StatusSincronizacao.SUCESSO));
    }
}
