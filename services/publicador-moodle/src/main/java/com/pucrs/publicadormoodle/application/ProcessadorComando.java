package com.pucrs.publicadormoodle.application;

import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import com.pucrs.publicadormoodle.domain.model.ComandoProcessado;
import com.pucrs.publicadormoodle.domain.model.ResultadoSincronizacao;
import com.pucrs.publicadormoodle.domain.port.out.ComandoRepositorio;
import com.pucrs.publicadormoodle.domain.port.out.Destino;
import com.pucrs.publicadormoodle.domain.port.out.MensageriaPort;
import com.pucrs.publicadormoodle.domain.port.out.Metricas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Trata um comando {@code SincronizarTurma.v1}:
 * <ol>
 *   <li><b>idempotência</b>: comando já concluído => reemite o resultado salvo e não toca no Moodle;</li>
 *   <li>comando <b>obsoleto</b> (versão menor que a já concluída da turma) => descartado;</li>
 *   <li>aplica no Moodle; sucesso/parcial/falha de negócio => grava na inbox e publica {@code TurmaSincronizada};</li>
 *   <li>Moodle indisponível => <b>retry por fila</b>: tentativa 1 -> retry-5s, 2 -> retry-30s, 3 -> DLQ + resultado FALHA.</li>
 * </ol>
 * O ack acontece quando este método retorna; se ele lançar exceção (ex.: broker não confirmou), a mensagem volta
 * à fila e o fluxo se repete com segurança (inbox + idnumber garantem a idempotência).
 */
@Service
public class ProcessadorComando {

    public static final int MAX_TENTATIVAS = 3;

    private static final Logger log = LoggerFactory.getLogger(ProcessadorComando.class);

    private final SincronizadorTurma sincronizador;
    private final ComandoRepositorio repositorio;
    private final MensageriaPort mensageria;
    private final Metricas metricas;

    public ProcessadorComando(SincronizadorTurma sincronizador, ComandoRepositorio repositorio,
                              MensageriaPort mensageria, Metricas metricas) {
        this.sincronizador = sincronizador;
        this.repositorio = repositorio;
        this.mensageria = mensageria;
        this.metricas = metricas;
    }

    public void processar(ComandoRecebido c, int tentativa) {
        long turmaId = c.turmaId();
        int versao = c.comando().versao();

        Optional<ComandoProcessado> jaFeito = repositorio.buscar(c.commandId());
        if (jaFeito.isPresent()) {
            log.info("[IDEMPOTENCIA] comando {} (turmaId={}) já foi concluído em {}: nada a refazer no Moodle; reenviando o resultado salvo ({})",
                    c.commandId(), turmaId, jaFeito.get().processadoEm(), jaFeito.get().status());
            mensageria.publicarResultado(c.commandId(), c.correlationId(), jaFeito.get().resultado());
            metricas.comando("duplicado");
            return;
        }
        int maior = repositorio.maiorVersaoProcessada(turmaId);
        if (versao < maior) {
            log.info("[IDEMPOTENCIA] comando {} da turmaId={} é obsoleto (versão {} < {} já aplicada): descartado",
                    c.commandId(), turmaId, versao, maior);
            metricas.comando("obsoleto");
            return;
        }

        log.info("[PROCESSA] aplicando turmaId={} versão={} no Moodle (tentativa {}/{}): {} alunos, {} professores, turma {}",
                turmaId, versao, tentativa, MAX_TENTATIVAS, c.comando().alunos().size(),
                c.comando().professores().size(), c.comando().turma().ativa() ? "ativa" : "inativa");

        ResultadoSincronizacao resultado;
        try {
            resultado = sincronizador.aplicar(c.comando()).comTentativas(tentativa);
        } catch (SistemaIndisponivelException e) {
            falhaTransitoria(c, tentativa, "Moodle indisponível", e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.error("[PROCESSA] erro inesperado ao aplicar turmaId={}", turmaId, e);
            falhaTransitoria(c, tentativa, "Falha inesperada", String.valueOf(e));
            return;
        }

        // grava a inbox (com o resultado) e só depois publica: se a publicação falhar, a reentrega reemite o resultado
        repositorio.salvar(c.commandId(), resultado);
        log.info("[BANCO] comando_processado gravado: commandId={} turmaId={} status={}", c.commandId(), turmaId, resultado.status());
        log.info("[RESULTADO] turmaId={} status={} resumo={} erros={}", turmaId, resultado.status(), resultado.resumo(),
                resultado.erros().size());
        mensageria.publicarResultado(c.commandId(), c.correlationId(), resultado);
        metricas.comando(resultado.status().name().toLowerCase());
    }

    private void falhaTransitoria(ComandoRecebido c, int tentativa, String titulo, String causa) {
        long turmaId = c.turmaId();
        if (tentativa < MAX_TENTATIVAS) {
            Destino destino = tentativa == 1 ? Destino.RETRY_5S : Destino.RETRY_30S;
            int proxima = tentativa + 1;
            log.warn("[RETRY] {}; reenfileirado em {}, tentativa {}/{} (turmaId={}, commandId={}) causa: {}",
                    titulo, destino.rotulo(), proxima, MAX_TENTATIVAS, turmaId, c.commandId(), causa);
            mensageria.reenfileirar(c, destino, proxima);
            metricas.comando("retry");
            return;
        }
        String erro = titulo + " após " + tentativa + " tentativas; comando enviado à DLQ para reprocessamento manual. Causa: " + causa;
        log.error("[DLQ] turmaId={} commandId={}: {}", turmaId, c.commandId(), erro);
        mensageria.reenfileirar(c, Destino.DLQ, tentativa);
        ResultadoSincronizacao falha = ResultadoSincronizacao.falha(c.comando(), tentativa, erro);
        // não vai para a inbox: o reprocessamento da DLQ precisa poder executar o comando de novo
        log.info("[RESULTADO] turmaId={} status={} (tentativas={})", turmaId, falha.status(), tentativa);
        mensageria.publicarResultado(c.commandId(), c.correlationId(), falha);
        metricas.comando("dlq");
    }

    /** Mensagem que não é um SincronizarTurma.v1 válido: DLQ direto, sem retry. */
    public void rejeitarIlegivel(byte[] corpo, String messageId, String correlationId, int tentativa, String motivo) {
        log.error("[DLQ] mensagem ilegível enviada direto à DLQ (messageId={}): {}", messageId, motivo);
        mensageria.enviarIlegivelParaDlq(corpo, messageId, correlationId, tentativa, motivo);
        metricas.comando("ilegivel");
    }
}
