package com.pucrs.publicadormoodle.adapter;

import com.pucrs.publicadormoodle.adapter.in.messaging.ComandoIlegivelException;
import com.pucrs.publicadormoodle.adapter.in.messaging.ComandoParser;
import com.pucrs.publicadormoodle.application.ComandoRecebido;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComandoParserTest {

    static byte[] amostra() throws IOException {
        return new ClassPathResource("samples/comando-turma.json").getContentAsByteArray();
    }

    @Test
    void leOComandoIgnorandoCamposDesconhecidos() throws IOException {
        ComandoRecebido c = ComandoParser.parse(amostra(), null, null);

        assertThat(c.commandId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(c.correlationId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(c.comando().versao()).isEqualTo(3);
        assertThat(c.turmaId()).isEqualTo(10);
        assertThat(c.comando().turma().nomeNoMoodle()).isEqualTo("Ensino Fundamental — 1º Ano A");
        assertThat(c.comando().alunos()).hasSize(2);
        assertThat(c.comando().alunos().get(1).ativo()).isFalse();
        assertThat(c.comando().alunos().get(1).email()).isNull();
        assertThat(c.comando().professores().get(0).nome()).isEqualTo("Carlos Souza");
        assertThat(c.corpoOriginal()).isEqualTo(amostra());
    }

    @Test
    void jsonInvalidoEhIlegivel() {
        assertThatThrownBy(() -> ComandoParser.parse("{isso nao e json".getBytes(StandardCharsets.UTF_8), "m", "c"))
                .isInstanceOf(ComandoIlegivelException.class).hasMessageContaining("JSON inválido");
    }

    @Test
    void tipoErradoEhIlegivel() {
        String json = "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"tipo\":\"Outra\",\"versao\":1,\"dados\":{}}";
        assertThatThrownBy(() -> ComandoParser.parse(json.getBytes(StandardCharsets.UTF_8), null, null))
                .isInstanceOf(ComandoIlegivelException.class).hasMessageContaining("tipo inesperado");
    }

    @Test
    void versaoDeContratoNaoSuportadaEhIlegivel() {
        String json = "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"tipo\":\"SincronizarTurma\",\"versao\":2,\"dados\":{}}";
        assertThatThrownBy(() -> ComandoParser.parse(json.getBytes(StandardCharsets.UTF_8), null, null))
                .isInstanceOf(ComandoIlegivelException.class).hasMessageContaining("v2");
    }

    @Test
    void semTurmaEhIlegivel() {
        String json = "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"tipo\":\"SincronizarTurma\",\"versao\":1,\"dados\":{\"alunos\":[]}}";
        assertThatThrownBy(() -> ComandoParser.parse(json.getBytes(StandardCharsets.UTF_8), null, null))
                .isInstanceOf(ComandoIlegivelException.class).hasMessageContaining("turma");
    }

    @Test
    void usaOMessageIdQuandoOEventIdNaoVemNoCorpo() {
        String json = "{\"tipo\":\"SincronizarTurma\",\"versao\":1,\"dados\":{\"turma\":{\"id\":1}}}";
        ComandoRecebido c = ComandoParser.parse(json.getBytes(StandardCharsets.UTF_8), "33333333-3333-3333-3333-333333333333", "corr");

        assertThat(c.commandId()).isEqualTo(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        assertThat(c.correlationId()).isEqualTo("corr");
    }
}
