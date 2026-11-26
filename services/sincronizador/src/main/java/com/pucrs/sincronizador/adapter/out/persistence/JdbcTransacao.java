package com.pucrs.sincronizador.adapter.out.persistence;

import com.pucrs.sincronizador.domain.port.out.Transacao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class JdbcTransacao implements Transacao {

    private final TransactionTemplate template;

    public JdbcTransacao(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T executar(Supplier<T> bloco) {
        return template.execute(status -> bloco.get());
    }
}
