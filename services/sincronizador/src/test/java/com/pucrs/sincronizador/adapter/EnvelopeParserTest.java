package com.pucrs.sincronizador.adapter;

import com.pucrs.sincronizador.adapter.in.messaging.EnvelopeParser;
import com.pucrs.sincronizador.adapter.in.messaging.MensagemInvalidaException;
import com.pucrs.sincronizador.config.JsonConfig;
import com.pucrs.sincronizador.domain.model.EventoTurmaAlterada;
import com.pucrs.sincronizador.domain.model.ResultadoSincronizacao;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeParserTest {

    final EnvelopeParser parser = new EnvelopeParser(new JsonConfig().jsonMapper());
    static final String ID = "3f2c9b1e-7a54-4d2e-9d0a-1b2c3d4e5f60";

    static Message msg(String json) {
        return MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8)).build();
    }

    static String turmaAlterada(String extra) {
        return """
                {"eventId":"%s","tipo":"TurmaAlterada","versao":1,"ocorridoEm":"2026-11-26T12:00:00Z",
                 "correlationId":"corr-9","origem":"monitor-ieducar","campoNovoDoEnvelope":true,
                 "dados":{"mudanca":"CRIADA","hash":"abc",%s
                   "turma":{"id":10,"nome":"1º Ano A","ano":2026,"ativa":true,"escolaId":1,"escolaNome":"Escola X",
                            "cursoId":3,"cursoNome":"Ensino Fundamental","campoNovo":"x"},
                   "alunos":[{"id":7,"nome":"Ana","email":null,"ativo":true,"apelido":"aninha"}],
                   "professores":[]}}""".formatted(ID, extra);
    }

    @Test
    void parseTolerante_ignoraCamposDesconhecidos() {
        EventoTurmaAlterada e = parser.turmaAlterada(msg(turmaAlterada("\"novidade\":{\"a\":1},")));

        assertThat(e.eventId().toString()).isEqualTo(ID);
        assertThat(e.correlationId()).isEqualTo("corr-9");
        assertThat(e.mudanca()).isEqualTo("CRIADA");
        assertThat(e.dados().turma().nome()).isEqualTo("1º Ano A");
        assertThat(e.dados().turma().cursoId()).isEqualTo(3);
        assertThat(e.dados().alunos()).hasSize(1);
        assertThat(e.dados().alunos().get(0).email()).isNull();
        assertThat(e.dados().professores()).isEmpty();
    }

    @Test
    void poison_jsonQuebrado_eRejeitado() {
        assertThatThrownBy(() -> parser.turmaAlterada(msg("{isto não é json")))
                .isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> parser.turmaAlterada(msg("")))
                .isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    void tipoOuVersaoDesconhecidos_saoRejeitados() {
        String v2 = turmaAlterada("").replace("\"versao\":1", "\"versao\":2");
        assertThatThrownBy(() -> parser.turmaAlterada(msg(v2)))
                .isInstanceOf(MensagemInvalidaException.class).hasMessageContaining("TurmaAlterada.v2");
        String outro = turmaAlterada("").replace("\"TurmaAlterada\"", "\"AlunoExcluido\"");
        assertThatThrownBy(() -> parser.turmaAlterada(msg(outro))).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    void semTurma_ouSemEventId_saoRejeitados() {
        assertThatThrownBy(() -> parser.turmaAlterada(msg(
                "{\"eventId\":\"" + ID + "\",\"tipo\":\"TurmaAlterada\",\"versao\":1,\"dados\":{}}")))
                .isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> parser.turmaAlterada(msg(turmaAlterada("").replace(ID, "nao-e-uuid"))))
                .isInstanceOf(MensagemInvalidaException.class);
    }

    @Test
    void resultado_eInterpretadoComMapeamentos() {
        String json = """
                {"eventId":"%s","tipo":"TurmaSincronizada","versao":1,"correlationId":"corr-9","origem":"publicador-moodle",
                 "dados":{"turmaId":10,"versao":3,"status":"PARCIAL","categoriaMoodleId":4,"cursoMoodleId":12,
                  "resumo":{"cursosCriados":1,"cursosAtualizados":0,"cursosOcultados":0,"usuariosCriados":2,
                            "usuariosAtualizados":0,"inscricoesCriadas":2,"inscricoesSuspensas":0,"extra":1},
                  "mapeamentos":[{"tipo":"CURSO","ieducarId":10,"moodleId":12,"idnumber":"IEDUCAR-TURMA-10"}],
                  "erros":["aluno 9: e-mail recusado"],"tentativas":2}}""".formatted(ID);

        ResultadoSincronizacao r = parser.turmaSincronizada(msg(json));

        assertThat(r.turmaId()).isEqualTo(10);
        assertThat(r.versao()).isEqualTo(3);
        assertThat(r.estado().name()).isEqualTo("PARCIAL");
        assertThat(r.cursoMoodleId()).isEqualTo(12);
        assertThat(r.resumo().usuariosCriados()).isEqualTo(2);
        assertThat(r.mapeamentos()).hasSize(1);
        assertThat(r.erros()).containsExactly("aluno 9: e-mail recusado");
        assertThat(r.tentativas()).isEqualTo(2);
    }
}
