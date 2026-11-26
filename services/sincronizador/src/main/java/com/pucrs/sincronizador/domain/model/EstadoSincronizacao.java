package com.pucrs.sincronizador.domain.model;

/** ENVIADA: comando publicado, aguardando o resultado. Depois: SINCRONIZADA, PARCIAL ou FALHA. */
public enum EstadoSincronizacao {
    ENVIADA, SINCRONIZADA, PARCIAL, FALHA
}
