package com.pucrs.publicadormoodle.adapter.in.web;

import com.pucrs.publicadormoodle.adapter.out.moodle.FalhaInjetavel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Simula falhas do Moodle (fora do ar, resposta de criação perdida, latência). Só existe com FALHA_INJETAVEL=true; sem a flag o caminho responde 404. */
@RestController
@RequestMapping("/api/falhas")
@ConditionalOnProperty(prefix = "falha-injetavel", name = "habilitada", havingValue = "true")
public class FalhasController {

    private final FalhaInjetavel falha;

    public FalhasController(FalhaInjetavel falha) {
        this.falha = falha;
    }

    /** Qualquer subconjunto dos campos; o que não vier fica como está. */
    public record FalhaRequest(Boolean indisponivel, Integer perderRespostaNaCriacao, Long latenciaMs) {
    }

    @GetMapping("/moodle")
    public Map<String, Object> consultar() {
        return falha.estado();
    }

    @PostMapping("/moodle")
    public Map<String, Object> definir(@RequestBody FalhaRequest corpo) {
        if (corpo.indisponivel() != null) {
            falha.definirIndisponivel(corpo.indisponivel());
        }
        if (corpo.perderRespostaNaCriacao() != null) {
            falha.definirPerderRespostaNaCriacao(corpo.perderRespostaNaCriacao());
        }
        if (corpo.latenciaMs() != null) {
            falha.definirLatenciaMs(corpo.latenciaMs());
        }
        return falha.estado();
    }
}
