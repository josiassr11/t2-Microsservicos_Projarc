package com.pucrs.publicadormoodle.adapter.in.web;

import com.pucrs.publicadormoodle.application.AdministracaoService;
import com.pucrs.publicadormoodle.config.Topologia;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dlq")
public class DlqController {

    private final AdministracaoService servico;

    public DlqController(AdministracaoService servico) {
        this.servico = servico;
    }

    @GetMapping
    public Map<String, Object> quantidade() {
        return Map.of("fila", Topologia.FILA_DLQ, "mensagens", servico.mensagensNaDlq());
    }

    /** Devolve as mensagens da DLQ à fila principal (use depois de consertar a causa, ex.: Moodle de volta). */
    @PostMapping("/reprocessar")
    public Map<String, Object> reprocessar() {
        return Map.of("fila", Topologia.FILA_DLQ, "reprocessadas", servico.reprocessarDlq());
    }
}
