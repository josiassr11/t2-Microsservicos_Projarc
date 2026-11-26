package com.pucrs.sincronizador.domain.port.in;

import com.pucrs.sincronizador.domain.model.MapeamentoItem;
import com.pucrs.sincronizador.domain.model.TurmaSincronizacao;

import java.util.List;
import java.util.Optional;

public interface ConsultaEstadoUseCase {

    List<TurmaSincronizacao> listarEstado();

    Optional<TurmaSincronizacao> estadoDaTurma(long turmaId);

    List<MapeamentoItem> listarMapeamentos();
}
