package com.pucrs.monitorieducar.domain.model;

public enum StatusVarredura {
    EM_ANDAMENTO,
    /** Tudo lido e publicado sem erro. */
    SUCESSO,
    /** Leitura ok, mas alguma publicação falhou (o snapshot dessas turmas não avançou). */
    PARCIAL,
    /** Não foi possível ler o iEducar: nada foi publicado. */
    FALHA
}
