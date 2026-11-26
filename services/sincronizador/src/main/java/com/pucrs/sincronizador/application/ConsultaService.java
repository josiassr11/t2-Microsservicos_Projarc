package com.pucrs.sincronizador.application;

import com.pucrs.sincronizador.domain.model.MapeamentoItem;
import com.pucrs.sincronizador.domain.model.TurmaSincronizacao;
import com.pucrs.sincronizador.domain.port.in.ConsultaEstadoUseCase;
import com.pucrs.sincronizador.domain.port.out.MapeamentoRepository;
import com.pucrs.sincronizador.domain.port.out.TurmaSincronizacaoRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ConsultaService implements ConsultaEstadoUseCase {

    private final TurmaSincronizacaoRepository turmas;
    private final MapeamentoRepository mapeamentos;

    public ConsultaService(TurmaSincronizacaoRepository turmas, MapeamentoRepository mapeamentos) {
        this.turmas = turmas;
        this.mapeamentos = mapeamentos;
    }

    @Override
    public List<TurmaSincronizacao> listarEstado() {
        return turmas.listar();
    }

    @Override
    public Optional<TurmaSincronizacao> estadoDaTurma(long turmaId) {
        return turmas.buscar(turmaId);
    }

    @Override
    public List<MapeamentoItem> listarMapeamentos() {
        return mapeamentos.listar();
    }
}
