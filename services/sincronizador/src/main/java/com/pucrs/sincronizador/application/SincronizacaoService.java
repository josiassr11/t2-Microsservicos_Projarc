package com.pucrs.sincronizador.application;

import com.pucrs.sincronizador.domain.model.ComandoSincronizar;
import com.pucrs.sincronizador.domain.model.EstadoSincronizacao;
import com.pucrs.sincronizador.domain.model.EventoTurmaAlterada;
import com.pucrs.sincronizador.domain.model.IdsDeterministicos;
import com.pucrs.sincronizador.domain.model.ResultadoProcessamento;
import com.pucrs.sincronizador.domain.model.ResultadoSincronizacao;
import com.pucrs.sincronizador.domain.model.TurmaInfo;
import com.pucrs.sincronizador.domain.model.TurmaSincronizacao;
import com.pucrs.sincronizador.domain.port.in.ProcessarMensagensUseCase;
import com.pucrs.sincronizador.domain.port.out.ComandoPublisher;
import com.pucrs.sincronizador.domain.port.out.EventoProcessadoRepository;
import com.pucrs.sincronizador.domain.port.out.MapeamentoRepository;
import com.pucrs.sincronizador.domain.port.out.Transacao;
import com.pucrs.sincronizador.domain.port.out.TurmaSincronizacaoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Coração do sincronizador. Cada método roda em UMA transação (idempotência + estado + publicação do comando):
 * se a publicação falhar, o registro do evento é desfeito e a mensagem será reentregue.
 */
@Service
public class SincronizacaoService implements ProcessarMensagensUseCase {

    private static final Logger log = LoggerFactory.getLogger(SincronizacaoService.class);

    static final String TIPO_EVENTO = "TurmaAlterada";
    static final String TIPO_RESULTADO = "TurmaSincronizada";

    private final Transacao transacao;
    private final EventoProcessadoRepository eventos;
    private final TurmaSincronizacaoRepository turmas;
    private final MapeamentoRepository mapeamentos;
    private final ComandoPublisher publisher;

    public SincronizacaoService(Transacao transacao, EventoProcessadoRepository eventos,
                                TurmaSincronizacaoRepository turmas, MapeamentoRepository mapeamentos,
                                ComandoPublisher publisher) {
        this.transacao = transacao;
        this.eventos = eventos;
        this.turmas = turmas;
        this.mapeamentos = mapeamentos;
        this.publisher = publisher;
    }

    @Override
    public ResultadoProcessamento processarTurmaAlterada(EventoTurmaAlterada evento) {
        TurmaInfo turma = evento.dados().turma();
        return transacao.executar(() -> {
            if (!eventos.registrar(evento.eventId(), TIPO_EVENTO, evento.correlationId())) {
                log.info("[IDEMPOTENCIA] evento {} já processado (turma {}): duplicata descartada, nada foi refeito",
                        evento.eventId(), turma.id());
                return ResultadoProcessamento.DUPLICADA;
            }
            log.info("[BANCO] idempotência: evento {} gravado como processado (vale só se a transação confirmar)", evento.eventId());

            int versao = turmas.registrarEnvio(turma, evento.dados().hash(), evento.eventId());
            log.info("[PROCESSA] turma {} ({}) v{} -> {} (mudança {})", turma.id(), turma.nome(), versao,
                    EstadoSincronizacao.ENVIADA, evento.mudanca());

            ComandoSincronizar comando = new ComandoSincronizar(IdsDeterministicos.commandId(evento.eventId()),
                    evento.correlationId(), versao, evento.dados());
            publisher.publicar(comando);
            return ResultadoProcessamento.PROCESSADA;
        });
    }

    @Override
    public ResultadoProcessamento processarResultado(ResultadoSincronizacao r) {
        return transacao.executar(() -> {
            if (!eventos.registrar(r.eventId(), TIPO_RESULTADO, r.correlationId())) {
                log.info("[IDEMPOTENCIA] resultado {} já processado (turma {}): duplicata descartada",
                        r.eventId(), r.turmaId());
                return ResultadoProcessamento.DUPLICADA;
            }
            Optional<TurmaSincronizacao> atual = turmas.buscar(r.turmaId());
            String anterior = atual.map(t -> t.estado() + " v" + t.versao()).orElse("(desconhecida)");
            String erro = r.erros().isEmpty() ? null : String.join(" | ", r.erros());

            boolean aplicado = turmas.registrarResultado(r.turmaId(), r.versao(), r.estado(), r.categoriaMoodleId(),
                    r.cursoMoodleId(), r.resumo(), erro, r.eventId());
            if (!aplicado) {
                log.warn("[RESULTADO] turma {} v{} {} é OBSOLETO (estado atual {}): apenas registrado, estado mantido",
                        r.turmaId(), r.versao(), r.status(), anterior);
                return ResultadoProcessamento.OBSOLETA;
            }
            mapeamentos.salvar(r.mapeamentos());
            log.info("[RESULTADO] turma {} v{}: {} -> {} (curso Moodle {}, {} mapeamentos, {} erros, {} tentativa(s))",
                    r.turmaId(), r.versao(), anterior, r.estado(), r.cursoMoodleId(), r.mapeamentos().size(),
                    r.erros().size(), r.tentativas());
            if (!r.erros().isEmpty()) {
                r.erros().forEach(e -> log.warn("[RESULTADO] turma {} erro informado pelo publicador: {}", r.turmaId(), e));
            }
            return ResultadoProcessamento.PROCESSADA;
        });
    }
}
