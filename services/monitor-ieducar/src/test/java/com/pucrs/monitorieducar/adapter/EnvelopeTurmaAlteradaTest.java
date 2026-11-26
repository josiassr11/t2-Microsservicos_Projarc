package com.pucrs.monitorieducar.adapter;

import com.pucrs.monitorieducar.adapter.out.messaging.EnvelopeTurmaAlterada;
import com.pucrs.monitorieducar.domain.model.Curso;
import com.pucrs.monitorieducar.domain.model.EventoTurmaAlterada;
import com.pucrs.monitorieducar.domain.model.Participante;
import com.pucrs.monitorieducar.domain.model.TipoMudanca;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.TurmaSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O corpo da mensagem tem de bater com docs/contratos.md (seções 2 e 3.1). */
class EnvelopeTurmaAlteradaTest {

    @Test
    void corpoSegueOContrato() {
        UUID eventId = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        Turma turma = new Turma(10, "1º Ano A", 2026, true, 1, "Escola X", new Curso(3, "Ensino Fundamental", true));
        TurmaSnapshot snap = new TurmaSnapshot(turma,
                List.of(new Participante(7, "Ana Silva", "ana@x.com", true), new Participante(8, "Bia", null, false)),
                List.of(new Participante(2, "Carlos Souza", null, true)));
        var evento = new EventoTurmaAlterada(eventId, corr, Instant.parse("2026-11-26T12:00:00Z"),
                TipoMudanca.CRIADA, "abc123", snap);

        JsonNode j = JsonMapper.builder().build().readTree(EnvelopeTurmaAlterada.paraJson(evento));

        assertThat(j.path("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(j.path("tipo").asString()).isEqualTo("TurmaAlterada");
        assertThat(j.path("versao").asInt()).isEqualTo(1);
        assertThat(j.path("ocorridoEm").asString()).isEqualTo("2026-11-26T12:00:00Z");
        assertThat(j.path("correlationId").asString()).isEqualTo(corr.toString());
        assertThat(j.path("origem").asString()).isEqualTo("monitor-ieducar");
        JsonNode d = j.path("dados");
        assertThat(d.path("mudanca").asString()).isEqualTo("CRIADA");
        assertThat(d.path("hash").asString()).isEqualTo("abc123");
        assertThat(d.path("turma").path("id").asInt()).isEqualTo(10);
        assertThat(d.path("turma").path("nome").asString()).isEqualTo("1º Ano A");
        assertThat(d.path("turma").path("ano").asInt()).isEqualTo(2026);
        assertThat(d.path("turma").path("ativa").asBoolean()).isTrue();
        assertThat(d.path("turma").path("escolaId").asInt()).isEqualTo(1);
        assertThat(d.path("turma").path("escolaNome").asString()).isEqualTo("Escola X");
        assertThat(d.path("turma").path("cursoId").asInt()).isEqualTo(3);
        assertThat(d.path("turma").path("cursoNome").asString()).isEqualTo("Ensino Fundamental");
        assertThat(d.path("alunos")).hasSize(2);
        assertThat(d.path("alunos").get(0).path("email").asString()).isEqualTo("ana@x.com");
        assertThat(d.path("alunos").get(1).path("email").isNull()).isTrue();
        assertThat(d.path("alunos").get(1).path("ativo").asBoolean()).isFalse();
        assertThat(d.path("professores").get(0).path("nome").asString()).isEqualTo("Carlos Souza");
        assertThat(EnvelopeTurmaAlterada.tipoAmqp()).isEqualTo("TurmaAlterada.v1");
    }
}
