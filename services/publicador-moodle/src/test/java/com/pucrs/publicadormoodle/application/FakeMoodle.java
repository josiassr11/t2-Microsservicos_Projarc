package com.pucrs.publicadormoodle.application;

import com.pucrs.publicadormoodle.domain.exception.MoodleException;
import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import com.pucrs.publicadormoodle.domain.model.CursoMoodle;
import com.pucrs.publicadormoodle.domain.model.NovoUsuarioMoodle;
import com.pucrs.publicadormoodle.domain.model.UsuarioMoodle;
import com.pucrs.publicadormoodle.domain.port.out.MoodlePort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Moodle em memória, com a mesma semântica observada no Moodle real (idnumber único, e-mail único, etc.). */
class FakeMoodle implements MoodlePort {
    long seq = 100;
    boolean fora;
    int chamadas;
    int chamadasCriar;
    final Map<String, Long> categorias = new HashMap<>();
    final Map<String, CursoMoodle> cursos = new HashMap<>();
    final Map<String, UsuarioMoodle> usuarios = new HashMap<>();
    final Set<String> emailsUsados = new HashSet<>();
    /** cursoId -> (usuarioId -> suspenso?) */
    final Map<Long, Map<Long, Boolean>> inscricoes = new HashMap<>();
    final Map<String, Long> papeis = new HashMap<>();

    private void checar() {
        chamadas++;
        if (fora) {
            throw new SistemaIndisponivelException("Moodle", "connection refused");
        }
    }

    @Override public Optional<Long> buscarCategoria(String idNumber) { checar(); return Optional.ofNullable(categorias.get(idNumber)); }
    @Override public long criarCategoria(String nome, String idNumber) { checar(); chamadasCriar++; categorias.put(idNumber, ++seq); return seq; }
    @Override public Optional<CursoMoodle> buscarCurso(String idNumber) { checar(); return Optional.ofNullable(cursos.get(idNumber)); }

    @Override
    public long criarCurso(String nomeCompleto, String nomeCurto, String idNumber, long categoriaId, boolean visivel) {
        checar(); chamadasCriar++;
        if (cursos.containsKey(idNumber)) throw new MoodleException("courseidnumbertaken", idNumber);
        cursos.put(idNumber, new CursoMoodle(++seq, nomeCompleto, visivel, categoriaId));
        return seq;
    }

    @Override
    public void atualizarCurso(long cursoId, String nomeCompleto, boolean visivel) {
        checar();
        cursos.replaceAll((k, c) -> c.id() == cursoId ? new CursoMoodle(cursoId, nomeCompleto, visivel, c.categoriaId()) : c);
    }

    @Override public Optional<UsuarioMoodle> buscarUsuario(String idNumber) { checar(); return Optional.ofNullable(usuarios.get(idNumber)); }

    @Override
    public long criarUsuario(NovoUsuarioMoodle u) {
        checar(); chamadasCriar++;
        if (!emailsUsados.add(u.email().toLowerCase())) throw new MoodleException("invalidparameter", "email duplicado");
        usuarios.put(u.idNumber(), new UsuarioMoodle(++seq, u.primeiroNome(), u.sobrenome(), u.email()));
        return seq;
    }

    @Override
    public void atualizarUsuario(long usuarioId, String primeiroNome, String sobrenome, String email) {
        checar();
        usuarios.replaceAll((k, u) -> u.id() == usuarioId
                ? new UsuarioMoodle(usuarioId, primeiroNome, sobrenome, email != null ? email : u.email()) : u);
    }

    @Override
    public Set<Long> listarInscritosAtivos(long cursoId) {
        checar();
        Set<Long> ativos = new HashSet<>();
        inscricoes.getOrDefault(cursoId, Map.of()).forEach((u, suspenso) -> { if (!suspenso) ativos.add(u); });
        return ativos;
    }

    @Override
    public void inscrever(long cursoId, long usuarioId, long papelId, boolean suspenso) {
        checar();
        inscricoes.computeIfAbsent(cursoId, c -> new HashMap<>()).put(usuarioId, suspenso);
        papeis.put(cursoId + ":" + usuarioId, papelId);
    }
}
