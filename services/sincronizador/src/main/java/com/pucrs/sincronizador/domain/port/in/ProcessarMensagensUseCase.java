package com.pucrs.sincronizador.domain.port.in;

import com.pucrs.sincronizador.domain.model.EventoTurmaAlterada;
import com.pucrs.sincronizador.domain.model.ResultadoProcessamento;
import com.pucrs.sincronizador.domain.model.ResultadoSincronizacao;

public interface ProcessarMensagensUseCase {

    /** Registra o evento, atualiza o estado da turma e publica o comando para o publicador-moodle. */
    ResultadoProcessamento processarTurmaAlterada(EventoTurmaAlterada evento);

    /** Aplica o resultado devolvido pelo publicador-moodle (estado + mapeamentos). */
    ResultadoProcessamento processarResultado(ResultadoSincronizacao resultado);
}
