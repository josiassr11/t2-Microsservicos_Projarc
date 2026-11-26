package com.pucrs.publicadormoodle.adapter.out.persistence;

import com.pucrs.publicadormoodle.config.JsonSupport;
import com.pucrs.publicadormoodle.domain.model.ComandoProcessado;
import com.pucrs.publicadormoodle.domain.model.ResultadoSincronizacao;
import com.pucrs.publicadormoodle.domain.model.StatusSincronizacao;
import com.pucrs.publicadormoodle.domain.port.out.ComandoRepositorio;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcComandoRepositorio implements ComandoRepositorio {

    private static final String COLUNAS = "command_id, turma_id, turma_versao, status, resultado, processado_em";

    private final JdbcClient jdbc;

    public JdbcComandoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ComandoProcessado> buscar(UUID commandId) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM comando_processado WHERE command_id = :id")
                .param("id", commandId)
                .query(this::mapear)
                .optional();
    }

    @Override
    public boolean salvar(UUID commandId, ResultadoSincronizacao r) {
        String json;
        try {
            json = JsonSupport.MAPPER.writeValueAsString(r);
        } catch (JacksonException e) {
            throw new IllegalStateException("Não foi possível serializar o resultado", e);
        }
        return jdbc.sql("""
                        INSERT INTO comando_processado (command_id, turma_id, turma_versao, status, resultado)
                        VALUES (:id, :turma, :versao, :status, :resultado)
                        ON CONFLICT (command_id) DO NOTHING
                        """)
                .param("id", commandId)
                .param("turma", r.turmaId())
                .param("versao", r.versao())
                .param("status", r.status().name())
                .param("resultado", json)
                .update() > 0;
    }

    @Override
    public int maiorVersaoProcessada(long turmaId) {
        return jdbc.sql("SELECT COALESCE(MAX(turma_versao), 0) FROM comando_processado WHERE turma_id = :t")
                .param("t", turmaId)
                .query(Integer.class)
                .single();
    }

    @Override
    public List<ComandoProcessado> ultimos(int quantidade) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM comando_processado ORDER BY processado_em DESC LIMIT :n")
                .param("n", quantidade)
                .query(this::mapear)
                .list();
    }

    private ComandoProcessado mapear(ResultSet rs, int linha) throws SQLException {
        try {
            ResultadoSincronizacao resultado = JsonSupport.MAPPER.readValue(rs.getString("resultado"), ResultadoSincronizacao.class);
            return new ComandoProcessado(rs.getObject("command_id", UUID.class), rs.getLong("turma_id"),
                    rs.getInt("turma_versao"), StatusSincronizacao.valueOf(rs.getString("status")), resultado,
                    rs.getObject("processado_em", OffsetDateTime.class).toInstant());
        } catch (JacksonException e) {
            throw new SQLException("Resultado salvo ilegível para " + rs.getString("command_id"), e);
        }
    }
}
