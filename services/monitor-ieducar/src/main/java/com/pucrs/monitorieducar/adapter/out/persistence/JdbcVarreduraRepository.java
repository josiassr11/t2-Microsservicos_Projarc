package com.pucrs.monitorieducar.adapter.out.persistence;

import com.pucrs.monitorieducar.domain.model.ResultadoVarredura;
import com.pucrs.monitorieducar.domain.model.StatusVarredura;
import com.pucrs.monitorieducar.domain.model.TipoVarredura;
import com.pucrs.monitorieducar.domain.port.out.VarreduraRepositoryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcVarreduraRepository implements VarreduraRepositoryPort {

    private final JdbcClient jdbc;

    public JdbcVarreduraRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long iniciar(String correlationId, TipoVarredura tipo, Instant inicio) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("insert into varredura (correlation_id, inicio, tipo, status) values (:corr, :inicio, :tipo, :status)")
                .param("corr", correlationId).param("inicio", Timestamp.from(inicio))
                .param("tipo", tipo.name()).param("status", StatusVarredura.EM_ANDAMENTO.name())
                .update(keys, "id");
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    @Override
    public void concluir(long id, ResultadoVarredura r) {
        jdbc.sql("""
                update varredura set fim = :fim, status = :status, turmas_lidas = :lidas,
                       eventos_publicados = :publicados, erro = :erro where id = :id
                """)
                .param("fim", Timestamp.from(r.fim())).param("status", r.status().name())
                .param("lidas", r.turmasLidas()).param("publicados", r.eventosPublicados())
                .param("erro", r.erro()).param("id", id).update();
    }

    @Override
    public Optional<ResultadoVarredura> ultima() {
        return jdbc.sql("""
                        select id, correlation_id, inicio, fim, tipo, status, turmas_lidas, eventos_publicados, erro
                        from varredura order by id desc limit 1
                        """)
                .query((rs, n) -> new ResultadoVarredura(rs.getLong("id"), rs.getString("correlation_id"),
                        TipoVarredura.valueOf(rs.getString("tipo")), StatusVarredura.valueOf(rs.getString("status")),
                        rs.getTimestamp("inicio").toInstant(),
                        rs.getTimestamp("fim") == null ? null : rs.getTimestamp("fim").toInstant(),
                        rs.getInt("turmas_lidas"), rs.getInt("eventos_publicados"), 0, 0, rs.getString("erro")))
                .optional();
    }
}
