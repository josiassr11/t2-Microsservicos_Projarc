package com.pucrs.sincronizador.domain.port.out;

import java.util.function.Supplier;

/** Executa o bloco em uma transação: se lançar exceção, tudo é desfeito. */
public interface Transacao {

    <T> T executar(Supplier<T> bloco);
}
