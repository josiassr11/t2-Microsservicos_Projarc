package com.pucrs.publicadormoodle.adapter.out.moodle;

import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chaves para simular falhas do Moodle nas demonstrações. Só funcionam com FALHA_INJETAVEL=true (e só são alteráveis
 * pelo endpoint /api/falhas/moodle, que também só existe com a flag ligada):
 * <ul>
 *   <li><b>indisponivel</b>: toda chamada falha antes de sair (Moodle fora do ar);</li>
 *   <li><b>perderRespostaNaCriacao=N</b>: as próximas N criações (categoria, curso, usuário) são EXECUTADAS no Moodle
 *       e depois falham como se a resposta tivesse se perdido;</li>
 *   <li><b>latenciaMs=N</b>: cada chamada ao Moodle espera N ms antes de sair (abre janela para derrubar o container).</li>
 * </ul>
 */
@Component
public class FalhaInjetavel {

    private static final Logger log = LoggerFactory.getLogger(FalhaInjetavel.class);

    private final boolean habilitada;
    private final AtomicBoolean indisponivel = new AtomicBoolean(false);
    private final AtomicInteger perderRespostaNaCriacao = new AtomicInteger(0);
    private final AtomicLong latenciaMs = new AtomicLong(0);
    private final AtomicBoolean latenciaAvisada = new AtomicBoolean(false);

    public FalhaInjetavel(@Value("${falha-injetavel.habilitada:false}") boolean habilitada) {
        this.habilitada = habilitada;
    }

    public boolean habilitada() {
        return habilitada;
    }

    public boolean indisponivel() {
        return habilitada && indisponivel.get();
    }

    public int perderRespostaNaCriacao() {
        return habilitada ? perderRespostaNaCriacao.get() : 0;
    }

    public long latenciaMs() {
        return habilitada ? latenciaMs.get() : 0;
    }

    /** Estado completo (GET e resposta do POST). */
    public Map<String, Object> estado() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("habilitada", habilitada);
        m.put("indisponivel", indisponivel());
        m.put("perderRespostaNaCriacao", perderRespostaNaCriacao());
        m.put("latenciaMs", latenciaMs());
        return m;
    }

    public void definirIndisponivel(boolean valor) {
        exigirHabilitada();
        indisponivel.set(valor);
        log.warn("[FALHA-INJETADA] Moodle marcado como {}", valor ? "INDISPONÍVEL (simulado)" : "disponível");
    }

    public void definirPerderRespostaNaCriacao(int n) {
        exigirHabilitada();
        perderRespostaNaCriacao.set(Math.max(0, n));
        log.warn("[FALHA-INJETADA] as próximas {} criações no Moodle serão executadas, mas a resposta será 'perdida' (simulado)",
                perderRespostaNaCriacao.get());
    }

    public void definirLatenciaMs(long ms) {
        exigirHabilitada();
        latenciaMs.set(Math.max(0, ms));
        latenciaAvisada.set(false);
        log.warn("[FALHA-INJETADA] latência artificial de {} ms antes de cada chamada ao Moodle", latenciaMs.get());
    }

    private void exigirHabilitada() {
        if (!habilitada) {
            throw new IllegalStateException("Injeção de falha desabilitada (FALHA_INJETAVEL=false)");
        }
    }

    /** Chamado antes de cada requisição ao Moodle: aplica a latência e a indisponibilidade simulada. */
    void antesDaChamada(String operacao) {
        long ms = latenciaMs();
        if (ms > 0) {
            if (latenciaAvisada.compareAndSet(false, true)) {
                log.warn("[FALHA-INJETADA] aguardando {} ms antes de chamar o Moodle ({}); latência ativa até ser zerada", ms, operacao);
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SistemaIndisponivelException("Moodle", "interrompido durante a latência injetada");
            }
        }
        if (indisponivel()) {
            log.warn("[FALHA-INJETADA] Moodle simulado como indisponível: chamada {} nem chegou a sair", operacao);
            throw new SistemaIndisponivelException("Moodle", "falha injetada (simulação de indisponibilidade)");
        }
    }

    /** Chamado DEPOIS de uma criação bem-sucedida no Moodle: se houver cota, finge que a resposta se perdeu. */
    void depoisDaCriacao(String operacao) {
        if (!habilitada) {
            return;
        }
        int restante = perderRespostaNaCriacao.getAndUpdate(n -> n > 0 ? n - 1 : 0);
        if (restante > 0) {
            log.warn("[FALHA-INJETADA] Moodle executou a criação {} mas a resposta foi 'perdida' (simulado); restam {} perda(s)",
                    operacao, restante - 1);
            throw new SistemaIndisponivelException("Moodle", "resposta perdida após " + operacao + " (falha injetada)");
        }
    }
}
