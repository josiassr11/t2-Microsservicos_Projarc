package com.pucrs.publicadormoodle.adapter.out.moodle;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import com.pucrs.publicadormoodle.domain.exception.SistemaIndisponivelException;
import com.pucrs.publicadormoodle.domain.model.CursoMoodle;
import com.pucrs.publicadormoodle.domain.model.NovoUsuarioMoodle;
import com.pucrs.publicadormoodle.domain.model.UsuarioMoodle;
import com.pucrs.publicadormoodle.domain.port.out.MoodlePort;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decorador do {@link MoodlePort}: <b>Retry</b> (só para {@link SistemaIndisponivelException}) por fora e
 * <b>CircuitBreaker</b> por dentro (cada tentativa conta no circuito). Circuito aberto => falha imediata
 * ("circuito aberto") sem esperar timeout e sem retry; a política de retry por fila assume dali.
 * Os timeouts de conexão/leitura ficam no cliente HTTP.
 * <p>
 * <b>Chamadas de CRIAÇÃO (categoria, curso, usuário) não têm retry dentro da chamada</b>: se o Moodle criou mas a
 * resposta se perdeu, repetir a criação daria erro de duplicidade. Elas só passam pelo circuit breaker/timeout; a falha
 * sobe como {@link SistemaIndisponivelException} e a política de retry por fila reexecuta o fluxo desde a busca por
 * idnumber (que encontra o que já foi criado e segue). Leituras, atualizações e inscrições são idempotentes e
 * continuam com retry.
 */
public class MoodleResiliente implements MoodlePort {

    private final MoodlePort destino;
    private final FalhaInjetavel falha;
    private final CircuitBreaker circuito;
    private final Retry retry;

    public MoodleResiliente(MoodlePort destino, FalhaInjetavel falha, CircuitBreaker circuito, Retry retry) {
        this.destino = destino;
        this.falha = falha;
        this.circuito = circuito;
        this.retry = retry;
    }

    private <T> T executar(String operacao, Supplier<T> chamada) {
        return executar(operacao, chamada, false);
    }

    /** Criação: sem retry na chamada; a injeção "perder resposta" executa a criação de verdade e depois falha. */
    private <T> T criar(String operacao, Supplier<T> chamada) {
        return executar(operacao, chamada, true);
    }

    private <T> T executar(String operacao, Supplier<T> chamada, boolean criacao) {
        Supplier<T> comFalha = () -> {
            falha.antesDaChamada(operacao);
            T resultado = chamada.get();
            if (criacao) {
                falha.depoisDaCriacao(operacao);
            }
            return resultado;
        };
        Supplier<T> decorada = CircuitBreaker.decorateSupplier(circuito, comFalha);
        if (!criacao) {
            decorada = Retry.decorateSupplier(retry, decorada);
        }
        try {
            return decorada.get();
        } catch (CallNotPermittedException e) {
            throw new SistemaIndisponivelException("Moodle", "circuito aberto (" + operacao + " não foi tentada)", e);
        }
    }

    private void executarSemRetorno(String operacao, Runnable chamada) {
        executar(operacao, () -> {
            chamada.run();
            return null;
        });
    }

    @Override
    public Optional<Long> buscarCategoria(String idNumber) {
        return executar("buscarCategoria", () -> destino.buscarCategoria(idNumber));
    }

    @Override
    public long criarCategoria(String nome, String idNumber) {
        return criar("criarCategoria", () -> destino.criarCategoria(nome, idNumber));
    }

    @Override
    public Optional<CursoMoodle> buscarCurso(String idNumber) {
        return executar("buscarCurso", () -> destino.buscarCurso(idNumber));
    }

    @Override
    public long criarCurso(String nomeCompleto, String nomeCurto, String idNumber, long categoriaId, boolean visivel) {
        return criar("criarCurso", () -> destino.criarCurso(nomeCompleto, nomeCurto, idNumber, categoriaId, visivel));
    }

    @Override
    public void atualizarCurso(long cursoId, String nomeCompleto, boolean visivel) {
        executarSemRetorno("atualizarCurso", () -> destino.atualizarCurso(cursoId, nomeCompleto, visivel));
    }

    @Override
    public Optional<UsuarioMoodle> buscarUsuario(String idNumber) {
        return executar("buscarUsuario", () -> destino.buscarUsuario(idNumber));
    }

    @Override
    public long criarUsuario(NovoUsuarioMoodle usuario) {
        return criar("criarUsuario", () -> destino.criarUsuario(usuario));
    }

    @Override
    public void atualizarUsuario(long usuarioId, String primeiroNome, String sobrenome, String email) {
        executarSemRetorno("atualizarUsuario", () -> destino.atualizarUsuario(usuarioId, primeiroNome, sobrenome, email));
    }

    @Override
    public Set<Long> listarInscritosAtivos(long cursoId) {
        return executar("listarInscritosAtivos", () -> destino.listarInscritosAtivos(cursoId));
    }

    @Override
    public void inscrever(long cursoId, long usuarioId, long papelId, boolean suspenso) {
        executarSemRetorno("inscrever", () -> destino.inscrever(cursoId, usuarioId, papelId, suspenso));
    }
}
