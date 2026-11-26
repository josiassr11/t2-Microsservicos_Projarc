package com.pucrs.sincronizador.adapter.out.persistence;

import com.pucrs.sincronizador.domain.port.out.EventoProcessadoRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcEventoProcessadoRepository implements EventoProcessadoRepository {

    private final JdbcClient jdbc;

    public JdbcEventoProcessadoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean registrar(UUID eventId, String tipo, String correlationId) {
        int linhas = jdbc.sql("""
                        INSERT INTO evento_processado (event_id, tipo, correlation_id)
                        VALUES (:id, :tipo, :corr)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", eventId).param("tipo", tipo).param("corr", correlationId)
                .update();
        return linhas > 0;
    }
}
