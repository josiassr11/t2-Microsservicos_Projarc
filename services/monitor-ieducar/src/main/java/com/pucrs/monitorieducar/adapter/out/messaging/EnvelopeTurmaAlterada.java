package com.pucrs.monitorieducar.adapter.out.messaging;

import com.pucrs.monitorieducar.domain.model.EventoTurmaAlterada;
import com.pucrs.monitorieducar.domain.model.Participante;
import com.pucrs.monitorieducar.domain.model.Turma;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** Monta o corpo JSON do envelope {@code TurmaAlterada.v1} (docs/contratos.md, seções 2 e 3.1). */
public final class EnvelopeTurmaAlterada {

    public static final String TIPO = "TurmaAlterada";
    public static final int VERSAO = 1;
    public static final String ORIGEM = "monitor-ieducar";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EnvelopeTurmaAlterada() {
    }

    public static String tipoAmqp() {
        return TIPO + ".v" + VERSAO;
    }

    public static String paraJson(EventoTurmaAlterada e) {
        Turma t = e.snapshot().turma();
        TurmaJson turma = new TurmaJson(t.id(), t.nome(), t.ano(), t.ativa(), t.escolaId(), t.escolaNome(),
                t.curso().id(), t.curso().nome());
        Dados dados = new Dados(e.mudanca().name(), e.hash(), turma, paraJson(e.snapshot().alunos()),
                paraJson(e.snapshot().professores()));
        Envelope envelope = new Envelope(e.eventId().toString(), TIPO, VERSAO, e.ocorridoEm().toString(),
                e.correlationId().toString(), ORIGEM, dados);
        return JSON.writeValueAsString(envelope);
    }

    private static List<ParticipanteJson> paraJson(List<Participante> lista) {
        return lista.stream().map(p -> new ParticipanteJson(p.id(), p.nome(), p.email(), p.ativo())).toList();
    }

    record Envelope(String eventId, String tipo, int versao, String ocorridoEm, String correlationId, String origem,
                    Dados dados) {
    }

    record Dados(String mudanca, String hash, TurmaJson turma, List<ParticipanteJson> alunos,
                 List<ParticipanteJson> professores) {
    }

    record TurmaJson(long id, String nome, int ano, boolean ativa, long escolaId, String escolaNome, long cursoId,
                     String cursoNome) {
    }

    record ParticipanteJson(long id, String nome, String email, boolean ativo) {
    }
}
