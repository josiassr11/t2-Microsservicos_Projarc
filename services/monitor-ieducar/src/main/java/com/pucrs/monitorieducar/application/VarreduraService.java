package com.pucrs.monitorieducar.application;

import com.pucrs.monitorieducar.domain.exception.PublicacaoFalhouException;
import com.pucrs.monitorieducar.domain.exception.VarreduraEmAndamentoException;
import com.pucrs.monitorieducar.domain.model.EventoTurmaAlterada;
import com.pucrs.monitorieducar.domain.model.RegistroSnapshot;
import com.pucrs.monitorieducar.domain.model.ResultadoVarredura;
import com.pucrs.monitorieducar.domain.model.StatusVarredura;
import com.pucrs.monitorieducar.domain.model.TipoMudanca;
import com.pucrs.monitorieducar.domain.model.TipoVarredura;
import com.pucrs.monitorieducar.domain.model.TurmaSnapshot;
import com.pucrs.monitorieducar.domain.port.in.VarreduraUseCase;
import com.pucrs.monitorieducar.domain.port.out.IEducarPort;
import com.pucrs.monitorieducar.domain.port.out.MetricasPort;
import com.pucrs.monitorieducar.domain.port.out.PublicadorEventosPort;
import com.pucrs.monitorieducar.domain.port.out.SnapshotRepositoryPort;
import com.pucrs.monitorieducar.domain.port.out.VarreduraRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coração do monitor: lê o iEducar, compara cada turma com o snapshot guardado no banco do próprio serviço e
 * publica {@code TurmaAlterada.v1} para o que mudou.
 * <p>
 * Regra de ouro: <b>o snapshot só avança depois que o broker confirmou a mensagem</b>. Se a publicação falhar
 * (RabbitMQ fora do ar, nack, mensagem sem fila), o snapshot fica como estava e a próxima varredura tenta de novo
 * — nenhuma mudança se perde.
 */
@Service
public class VarreduraService implements VarreduraUseCase {

    private static final Logger log = LoggerFactory.getLogger(VarreduraService.class);

    private final SnapshotMontador montador;
    private final PublicadorEventosPort publicador;
    private final SnapshotRepositoryPort snapshots;
    private final VarreduraRepositoryPort varreduras;
    private final MetricasPort metricas;
    private final ReentrantLock lock = new ReentrantLock();

    public VarreduraService(IEducarPort ieducar, PublicadorEventosPort publicador, SnapshotRepositoryPort snapshots,
                            VarreduraRepositoryPort varreduras, MetricasPort metricas) {
        this.montador = new SnapshotMontador(ieducar);
        this.publicador = publicador;
        this.snapshots = snapshots;
        this.varreduras = varreduras;
        this.metricas = metricas;
    }

    @Override
    public ResultadoVarredura varrer(TipoVarredura tipo) {
        if (!lock.tryLock()) {
            throw new VarreduraEmAndamentoException();
        }
        UUID correlationId = UUID.randomUUID();
        MDC.put("correlationId", correlationId.toString());
        try {
            return executar(tipo, correlationId);
        } finally {
            MDC.remove("correlationId");
            lock.unlock();
        }
    }

    private ResultadoVarredura executar(TipoVarredura tipo, UUID correlationId) {
        Instant inicio = Instant.now();
        long id = varreduras.iniciar(correlationId.toString(), tipo, inicio);
        log.info("[VARREDURA] iniciada id={} tipo={}", id, tipo);

        int lidas = 0;
        int publicadas = 0;
        int inalteradas = 0;
        int falhas = 0;
        StatusVarredura status;
        String erro = null;
        try {
            log.info("[VARREDURA] lendo turmas, matrículas e professores no iEducar...");
            List<TurmaSnapshot> lidos = montador.montar();
            lidas = lidos.size();
            log.info("[VARREDURA] leitura concluída: {} turma(s) no iEducar", lidas);

            for (TurmaSnapshot s : lidos) {
                long turmaId = s.turma().id();
                String hash = SnapshotHasher.hash(s);
                Optional<String> anterior = snapshots.buscarHash(turmaId);
                TipoMudanca mudanca = decidir(tipo, anterior, hash);
                if (mudanca == null) {
                    inalteradas++;
                    log.debug("[PROCESSA] turmaId={} sem mudança (hash={})", turmaId, curto(hash));
                    continue;
                }
                log.info("[PROCESSA] turmaId={} '{}' -> {} (hash anterior={}, novo={})", turmaId, s.turma().nome(),
                        mudanca, anterior.map(VarreduraService::curto).orElse("-"), curto(hash));
                EventoTurmaAlterada evento = new EventoTurmaAlterada(UUID.randomUUID(), correlationId, Instant.now(),
                        mudanca, hash, s);
                try {
                    publicador.publicar(evento);
                } catch (PublicacaoFalhouException e) {
                    falhas++;
                    log.error("[PUBLICA] FALHOU turmaId={} eventId={}: {} -> snapshot NÃO atualizado; será tentado "
                            + "de novo na próxima varredura", turmaId, evento.eventId(), e.getMessage());
                    continue;
                }
                publicadas++;
                snapshots.salvar(turmaId, hash, s.turma().nome(), s.turma().ativa());
                log.info("[BANCO] snapshot da turmaId={} atualizado (hash={})", turmaId, curto(hash));
            }
            status = falhas > 0 ? StatusVarredura.PARCIAL : StatusVarredura.SUCESSO;
        } catch (RuntimeException e) {
            status = StatusVarredura.FALHA;
            erro = e.getMessage();
            log.warn("[VARREDURA] ABORTADA, nada foi publicado: {} (nova tentativa na próxima varredura)", e.getMessage());
        }
        if (status == StatusVarredura.PARCIAL) {
            erro = falhas + " turma(s) não puderam ser publicadas";
        }

        Instant fim = Instant.now();
        ResultadoVarredura resultado = new ResultadoVarredura(id, correlationId.toString(), tipo, status, inicio, fim,
                lidas, publicadas, inalteradas, falhas, erro);
        varreduras.concluir(id, resultado);
        metricas.varreduraConcluida(status);
        log.info("[VARREDURA] concluída id={} status={} lidas={} publicadas={} inalteradas={} falhas={} em {}ms",
                id, status, lidas, publicadas, inalteradas, falhas, Duration.between(inicio, fim).toMillis());
        return resultado;
    }

    private static TipoMudanca decidir(TipoVarredura tipo, Optional<String> anterior, String hash) {
        if (tipo == TipoVarredura.COMPLETA) {
            return TipoMudanca.REPUBLICADA;
        }
        if (anterior.isEmpty()) {
            return TipoMudanca.CRIADA;
        }
        return anterior.get().equals(hash) ? null : TipoMudanca.ATUALIZADA;
    }

    private static String curto(String hash) {
        return hash.substring(0, 12);
    }

    @Override
    public Optional<ResultadoVarredura> ultimaVarredura() {
        return varreduras.ultima();
    }

    @Override
    public List<RegistroSnapshot> snapshots() {
        return snapshots.listar();
    }
}
