package com.pucrs.publicadormoodle.application;

import com.pucrs.publicadormoodle.domain.model.ComandoProcessado;
import com.pucrs.publicadormoodle.domain.port.out.ComandoRepositorio;
import com.pucrs.publicadormoodle.domain.port.out.DlqPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** Consultas e operações de apoio (demonstração e operação): últimos comandos e DLQ. */
@Service
public class AdministracaoService {

    private static final Logger log = LoggerFactory.getLogger(AdministracaoService.class);

    private final ComandoRepositorio repositorio;
    private final DlqPort dlq;

    public AdministracaoService(ComandoRepositorio repositorio, DlqPort dlq) {
        this.repositorio = repositorio;
        this.dlq = dlq;
    }

    public List<ComandoProcessado> ultimosComandos() {
        return repositorio.ultimos(50);
    }

    public int mensagensNaDlq() {
        return dlq.quantidade();
    }

    public int reprocessarDlq() {
        log.info("[DLQ] reprocessamento solicitado: movendo mensagens da DLQ de volta para a fila principal");
        int n = dlq.reprocessar();
        log.info("[DLQ] {} mensagem(ns) devolvida(s) à fila publicador-moodle.comandos (x-tentativa=1)", n);
        return n;
    }
}
