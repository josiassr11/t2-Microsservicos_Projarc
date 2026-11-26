package com.pucrs.sincronizador.adapter.out.persistence;

import com.pucrs.sincronizador.domain.model.MapeamentoItem;
import com.pucrs.sincronizador.domain.port.out.MapeamentoRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public class JdbcMapeamentoRepository implements MapeamentoRepository {

    private final JdbcClient jdbc;

    public JdbcMapeamentoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void salvar(Collection<MapeamentoItem> itens) {
        for (MapeamentoItem i : itens) {
            jdbc.sql("""
                            INSERT INTO mapeamento (tipo, ieducar_id, moodle_id, idnumber, atualizado_em)
                            VALUES (:tipo, :ieducar, :moodle, :idnumber, now())
                            ON CONFLICT (tipo, ieducar_id) DO UPDATE
                               SET moodle_id = EXCLUDED.moodle_id, idnumber = EXCLUDED.idnumber, atualizado_em = now()
                            """)
                    .param("tipo", i.tipo()).param("ieducar", i.ieducarId())
                    .param("moodle", i.moodleId()).param("idnumber", i.idnumber())
                    .update();
        }
    }

    @Override
    public List<MapeamentoItem> listar() {
        return jdbc.sql("SELECT tipo, ieducar_id, moodle_id, idnumber, atualizado_em FROM mapeamento ORDER BY tipo, ieducar_id")
                .query((rs, n) -> new MapeamentoItem(rs.getString("tipo"), rs.getLong("ieducar_id"),
                        rs.getLong("moodle_id"), rs.getString("idnumber"),
                        rs.getObject("atualizado_em", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }
}
