package com.pucrs.monitorieducar.application;

import com.pucrs.monitorieducar.domain.model.Matricula;
import com.pucrs.monitorieducar.domain.model.Participante;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.TurmaSnapshot;
import com.pucrs.monitorieducar.domain.model.VinculoProfessor;
import com.pucrs.monitorieducar.domain.port.out.IEducarPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lê tudo do iEducar (turmas, matrículas e vínculos de professores) e monta uma foto por turma.
 * Se qualquer leitura falhar, a exceção sobe e nada é publicado (a varredura inteira falha).
 * <p>
 * Só turmas <b>ativas</b> carregam participantes: turma inativa vai com listas vazias (o Moodle só precisa ocultar
 * o curso). Assim o hash de turmas inativas não muda por acaso quando outra turma do mesmo ano é desativada.
 */
public class SnapshotMontador {

    private final IEducarPort ieducar;

    public SnapshotMontador(IEducarPort ieducar) {
        this.ieducar = ieducar;
    }

    public List<TurmaSnapshot> montar() {
        List<Turma> turmas = ieducar.listarTurmas();

        Map<Integer, Set<Long>> escolasPorAno = new HashMap<>();
        for (Turma t : turmas) {
            if (t.ativa()) {
                escolasPorAno.computeIfAbsent(t.ano(), a -> new TreeSet<>()).add(t.escolaId());
            }
        }

        Map<Long, Map<Long, Participante>> alunos = new HashMap<>();
        Map<Long, Map<Long, Participante>> professores = new HashMap<>();
        for (var entrada : escolasPorAno.entrySet()) {
            int ano = entrada.getKey();
            for (Matricula m : ieducar.listarMatriculas(ano)) {
                juntar(alunos, m.turmaId(), new Participante(m.aluno().id(), m.aluno().nome(), m.aluno().email(), m.ativa()));
            }
            for (VinculoProfessor v : ieducar.listarVinculosProfessores(ano, entrada.getValue())) {
                juntar(professores, v.turmaId(), new Participante(v.professor().id(), v.professor().nome(),
                        v.professor().email(), v.ativo()));
            }
        }

        List<TurmaSnapshot> snapshots = new ArrayList<>();
        for (Turma t : turmas) {
            if (t.ativa()) {
                snapshots.add(new TurmaSnapshot(t, lista(alunos, t.id()), lista(professores, t.id())));
            } else {
                snapshots.add(new TurmaSnapshot(t, List.of(), List.of()));
            }
        }
        return snapshots;
    }

    /** Se o mesmo aluno/professor aparece mais de uma vez na turma, vale o ativo (mesma regra do T1). */
    private static void juntar(Map<Long, Map<Long, Participante>> mapa, long turmaId, Participante novo) {
        mapa.computeIfAbsent(turmaId, t -> new LinkedHashMap<>())
                .merge(novo.id(), novo, (a, b) -> a.ativo() ? a : b);
    }

    private static List<Participante> lista(Map<Long, Map<Long, Participante>> mapa, long turmaId) {
        Map<Long, Participante> m = mapa.get(turmaId);
        return m == null ? List.of() : List.copyOf(m.values());
    }
}
