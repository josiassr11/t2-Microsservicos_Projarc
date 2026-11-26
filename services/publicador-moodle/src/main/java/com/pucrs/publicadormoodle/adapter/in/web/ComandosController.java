package com.pucrs.publicadormoodle.adapter.in.web;

import com.pucrs.publicadormoodle.application.AdministracaoService;
import com.pucrs.publicadormoodle.domain.model.ComandoProcessado;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ComandosController {

    private final AdministracaoService servico;

    public ComandosController(AdministracaoService servico) {
        this.servico = servico;
    }

    /** Últimos 50 comandos concluídos, com o resultado enviado ao sincronizador. */
    @GetMapping("/comandos")
    public List<ComandoProcessado> comandos() {
        return servico.ultimosComandos();
    }
}
