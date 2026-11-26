package com.pucrs.monitorieducar.adapter.in.web;

import com.pucrs.monitorieducar.domain.exception.VarreduraEmAndamentoException;
import com.pucrs.monitorieducar.domain.model.RegistroSnapshot;
import com.pucrs.monitorieducar.domain.model.ResultadoVarredura;
import com.pucrs.monitorieducar.domain.model.TipoVarredura;
import com.pucrs.monitorieducar.domain.port.in.VarreduraUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class VarreduraController {

    private final VarreduraUseCase varredura;

    public VarreduraController(VarreduraUseCase varredura) {
        this.varredura = varredura;
    }

    /** Varredura incremental: publica só o que mudou desde o último snapshot. */
    @PostMapping("/varreduras")
    public ResultadoVarredura incremental() {
        return varredura.varrer(TipoVarredura.INCREMENTAL);
    }

    /** Varredura completa: republica todas as turmas (mudanca=REPUBLICADA). */
    @PostMapping("/varreduras/completa")
    public ResultadoVarredura completa() {
        return varredura.varrer(TipoVarredura.COMPLETA);
    }

    @GetMapping("/varreduras/ultima")
    public ResponseEntity<ResultadoVarredura> ultima() {
        return varredura.ultimaVarredura().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/snapshots")
    public List<RegistroSnapshot> snapshots() {
        return varredura.snapshots();
    }

    @ExceptionHandler(VarreduraEmAndamentoException.class)
    public ResponseEntity<Map<String, String>> emAndamento(VarreduraEmAndamentoException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", e.getMessage()));
    }
}
