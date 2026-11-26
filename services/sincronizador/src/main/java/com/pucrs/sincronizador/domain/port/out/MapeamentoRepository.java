package com.pucrs.sincronizador.domain.port.out;

import com.pucrs.sincronizador.domain.model.MapeamentoItem;

import java.util.Collection;
import java.util.List;

public interface MapeamentoRepository {

    void salvar(Collection<MapeamentoItem> itens);

    List<MapeamentoItem> listar();
}
