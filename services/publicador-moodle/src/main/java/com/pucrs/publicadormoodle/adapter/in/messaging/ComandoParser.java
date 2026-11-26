package com.pucrs.publicadormoodle.adapter.in.messaging;

import com.pucrs.publicadormoodle.application.ComandoRecebido;
import com.pucrs.publicadormoodle.config.JsonSupport;
import com.pucrs.publicadormoodle.domain.model.Participante;
import com.pucrs.publicadormoodle.domain.model.TurmaComando;
import com.pucrs.publicadormoodle.domain.model.TurmaDados;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lê o envelope + {@code dados} de um {@code SincronizarTurma.v1}. Leitor <b>tolerante</b>: campos desconhecidos são
 * ignorados e campos opcionais ausentes assumem padrões. Qualquer outra coisa é {@link ComandoIlegivelException}.
 */
public final class ComandoParser {

    private ComandoParser() {
    }

    public static ComandoRecebido parse(byte[] corpo, String messageId, String correlationIdProp) {
        JsonNode raiz;
        try {
            raiz = JsonSupport.MAPPER.readTree(corpo);
        } catch (JacksonException e) {
            throw new ComandoIlegivelException("JSON inválido: " + e.getOriginalMessage(), e);
        }
        if (raiz == null || !raiz.isObject()) {
            throw new ComandoIlegivelException("o corpo não é um objeto JSON");
        }
        String tipo = texto(raiz, "tipo");
        if (!"SincronizarTurma".equals(tipo)) {
            throw new ComandoIlegivelException("tipo inesperado: " + tipo);
        }
        int versaoMsg = raiz.path("versao").asInt(1);
        if (versaoMsg != 1) {
            throw new ComandoIlegivelException("versão de contrato não suportada: v" + versaoMsg);
        }
        UUID commandId = uuid(texto(raiz, "eventId"), messageId);
        if (commandId == null) {
            throw new ComandoIlegivelException("eventId ausente ou inválido");
        }
        String correlationId = texto(raiz, "correlationId");
        if (correlationId == null) {
            correlationId = correlationIdProp != null ? correlationIdProp : commandId.toString();
        }

        JsonNode dados = raiz.path("dados");
        JsonNode t = dados.path("turma");
        if (!t.isObject() || !t.has("id")) {
            throw new ComandoIlegivelException("dados.turma ausente");
        }
        TurmaDados turma = new TurmaDados(t.path("id").asLong(), texto(t, "nome") == null ? "" : texto(t, "nome"),
                t.path("ano").asInt(), t.path("ativa").asBoolean(true), t.path("escolaId").asLong(),
                texto(t, "escolaNome"), t.path("cursoId").asLong(), cursoNome(t));
        TurmaComando comando = new TurmaComando(dados.path("versao").asInt(1), texto(dados, "hash"), turma,
                participantes(dados.path("alunos")), participantes(dados.path("professores")));
        return new ComandoRecebido(commandId, correlationId, comando, corpo);
    }

    private static String cursoNome(JsonNode t) {
        String nome = texto(t, "cursoNome");
        return nome != null ? nome : "Curso " + t.path("cursoId").asLong();
    }

    private static List<Participante> participantes(JsonNode no) {
        List<Participante> lista = new ArrayList<>();
        if (no.isArray()) {
            for (JsonNode p : no) {
                lista.add(new Participante(p.path("id").asLong(), texto(p, "nome"), texto(p, "email"),
                        p.path("ativo").asBoolean(true)));
            }
        }
        return lista;
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.path(campo);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static UUID uuid(String... candidatos) {
        for (String c : candidatos) {
            if (c != null) {
                try {
                    return UUID.fromString(c);
                } catch (IllegalArgumentException ignorado) {
                    // tenta o próximo
                }
            }
        }
        return null;
    }
}
