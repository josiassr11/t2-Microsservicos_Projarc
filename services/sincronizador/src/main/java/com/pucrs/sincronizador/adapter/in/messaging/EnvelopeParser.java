package com.pucrs.sincronizador.adapter.in.messaging;

import com.pucrs.sincronizador.domain.model.DadosTurma;
import com.pucrs.sincronizador.domain.model.EventoTurmaAlterada;
import com.pucrs.sincronizador.domain.model.MapeamentoItem;
import com.pucrs.sincronizador.domain.model.Participante;
import com.pucrs.sincronizador.domain.model.Resumo;
import com.pucrs.sincronizador.domain.model.ResultadoSincronizacao;
import com.pucrs.sincronizador.domain.model.TurmaInfo;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Leitor tolerante dos envelopes: ignora campos desconhecidos (mudança compatível) e só rejeita o que não dá para
 * entender (JSON quebrado, tipo/versão que este serviço não conhece, campos essenciais ausentes).
 */
@Component
public class EnvelopeParser {

    private final JsonMapper json;

    public EnvelopeParser(JsonMapper json) {
        this.json = json;
    }

    public Envelope envelope(Message message, String tipoEsperado, int versaoEsperada) {
        JsonNode raiz;
        try {
            raiz = json.readTree(message.getBody());
        } catch (JacksonException e) {
            throw new MensagemInvalidaException("JSON ilegível: " + e.getOriginalMessage(), e);
        }
        if (raiz == null || !raiz.isObject()) {
            throw new MensagemInvalidaException("o corpo não é um objeto JSON");
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(texto(raiz, "eventId"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MensagemInvalidaException("eventId ausente ou inválido");
        }
        String tipo = texto(raiz, "tipo");
        int versao = raiz.path("versao").asInt(-1);
        if (!tipoEsperado.equals(tipo) || versao != versaoEsperada) {
            throw new MensagemInvalidaException("tipo/versão não suportado: " + tipo + ".v" + versao
                    + " (esperado " + tipoEsperado + ".v" + versaoEsperada + ")");
        }
        JsonNode dados = raiz.path("dados");
        if (!dados.isObject()) {
            throw new MensagemInvalidaException("campo 'dados' ausente");
        }
        String correlationId = texto(raiz, "correlationId");
        if (correlationId == null) {
            correlationId = message.getMessageProperties().getCorrelationId();
        }
        if (correlationId == null) {
            correlationId = eventId.toString();
        }
        return new Envelope(eventId, tipo, versao, correlationId, texto(raiz, "origem"), dados);
    }

    public EventoTurmaAlterada turmaAlterada(Message message) {
        Envelope e = envelope(message, "TurmaAlterada", 1);
        try {
            TurmaInfo turma = json.treeToValue(e.dados().path("turma"), TurmaInfo.class);
            if (turma == null || turma.id() <= 0) {
                throw new MensagemInvalidaException("dados.turma ausente ou sem id");
            }
            DadosTurma dados = new DadosTurma(texto(e.dados(), "hash"), turma,
                    participantes(e.dados().path("alunos")), participantes(e.dados().path("professores")));
            return new EventoTurmaAlterada(e.eventId(), e.correlationId(), texto(e.dados(), "mudanca"), dados);
        } catch (JacksonException ex) {
            throw new MensagemInvalidaException("dados da turma inválidos: " + ex.getOriginalMessage(), ex);
        }
    }

    public ResultadoSincronizacao turmaSincronizada(Message message) {
        Envelope e = envelope(message, "TurmaSincronizada", 1);
        JsonNode d = e.dados();
        if (!d.path("turmaId").isNumber() || !d.path("versao").isNumber()) {
            throw new MensagemInvalidaException("dados.turmaId/versao ausentes");
        }
        try {
            Resumo resumo = d.path("resumo").isObject() ? json.treeToValue(d.path("resumo"), Resumo.class) : null;
            List<MapeamentoItem> mapeamentos = new ArrayList<>();
            for (JsonNode m : d.path("mapeamentos")) {
                if (m.path("moodleId").isNumber() && m.path("ieducarId").isNumber() && texto(m, "tipo") != null) {
                    mapeamentos.add(new MapeamentoItem(texto(m, "tipo"), m.path("ieducarId").asLong(),
                            m.path("moodleId").asLong(), texto(m, "idnumber")));
                }
            }
            List<String> erros = new ArrayList<>();
            for (JsonNode er : d.path("erros")) {
                erros.add(er.asString());
            }
            return new ResultadoSincronizacao(e.eventId(), e.correlationId(), d.path("turmaId").asLong(),
                    d.path("versao").asInt(), texto(d, "status"), longOuNulo(d, "categoriaMoodleId"),
                    longOuNulo(d, "cursoMoodleId"), resumo, mapeamentos, erros, d.path("tentativas").asInt(1));
        } catch (JacksonException ex) {
            throw new MensagemInvalidaException("dados do resultado inválidos: " + ex.getOriginalMessage(), ex);
        }
    }

    private List<Participante> participantes(JsonNode lista) {
        List<Participante> resultado = new ArrayList<>();
        for (JsonNode p : lista) {
            resultado.add(json.treeToValue(p, Participante.class));
        }
        return resultado;
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.path(campo);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static Long longOuNulo(JsonNode no, String campo) {
        JsonNode v = no.path(campo);
        return v.isNumber() ? v.asLong() : null;
    }
}
