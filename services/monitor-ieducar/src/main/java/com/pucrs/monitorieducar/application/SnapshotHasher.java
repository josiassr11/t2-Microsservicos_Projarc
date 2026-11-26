package com.pucrs.monitorieducar.application;

import com.pucrs.monitorieducar.domain.model.Participante;
import com.pucrs.monitorieducar.domain.model.Turma;
import com.pucrs.monitorieducar.domain.model.TurmaSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Calcula o hash SHA-256 <b>canônico</b> de uma turma: o mesmo conteúdo sempre gera o mesmo hash, não importa a
 * ordem em que o iEducar devolveu os alunos/professores, e {@code null} é tratado como texto vazio.
 * O hash é o que o monitor compara com o snapshot para decidir se a turma mudou.
 */
public final class SnapshotHasher {

    private static final char SEP = '\u001F';
    private static final char LINHA = '\n';

    private SnapshotHasher() {
    }

    public static String hash(TurmaSnapshot s) {
        return sha256(canonico(s));
    }

    static String canonico(TurmaSnapshot s) {
        Turma t = s.turma();
        StringBuilder sb = new StringBuilder();
        sb.append("T").append(SEP).append(t.id()).append(SEP).append(n(t.nome())).append(SEP).append(t.ano())
                .append(SEP).append(b(t.ativa())).append(SEP).append(t.escolaId()).append(SEP).append(n(t.escolaNome()))
                .append(SEP).append(t.curso().id()).append(SEP).append(n(t.curso().nome())).append(LINHA);
        anexar(sb, "A", s.alunos());
        anexar(sb, "P", s.professores());
        return sb.toString();
    }

    private static void anexar(StringBuilder sb, String prefixo, List<Participante> participantes) {
        participantes.stream().sorted(Comparator.comparingLong(Participante::id)).forEach(p ->
                sb.append(prefixo).append(SEP).append(p.id()).append(SEP).append(n(p.nome())).append(SEP)
                        .append(n(p.email())).append(SEP).append(b(p.ativo())).append(LINHA));
    }

    private static String n(String texto) {
        return texto == null ? "" : texto.trim();
    }

    private static String b(boolean valor) {
        return valor ? "1" : "0";
    }

    private static String sha256(String texto) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
