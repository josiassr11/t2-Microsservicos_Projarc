package com.pucrs.sincronizador.adapter.in.web;

import com.pucrs.sincronizador.domain.model.EstadoSincronizacao;
import com.pucrs.sincronizador.domain.model.MapeamentoItem;
import com.pucrs.sincronizador.domain.model.TurmaSincronizacao;
import com.pucrs.sincronizador.domain.port.in.ConsultaEstadoUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Consulta ao estado da integração (somente leitura). */
@RestController
@RequestMapping("/api")
public class EstadoController {

    private final ConsultaEstadoUseCase consulta;

    public EstadoController(ConsultaEstadoUseCase consulta) {
        this.consulta = consulta;
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        List<TurmaSincronizacao> turmas = consulta.listarEstado();
        Map<EstadoSincronizacao, Long> porEstado = new EnumMap<>(EstadoSincronizacao.class);
        for (EstadoSincronizacao e : EstadoSincronizacao.values()) {
            porEstado.put(e, turmas.stream().filter(t -> t.estado() == e).count());
        }
        return Map.of("total", turmas.size(), "porEstado", porEstado, "turmas", turmas);
    }

    @GetMapping("/estado/{turmaId}")
    public TurmaSincronizacao estadoDaTurma(@PathVariable long turmaId) {
        return consulta.estadoDaTurma(turmaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "turma " + turmaId + " desconhecida"));
    }

    @GetMapping("/mapeamentos")
    public Map<String, Object> mapeamentos() {
        List<MapeamentoItem> itens = consulta.listarMapeamentos();
        return Map.of("total", itens.size(), "mapeamentos", itens);
    }
}
