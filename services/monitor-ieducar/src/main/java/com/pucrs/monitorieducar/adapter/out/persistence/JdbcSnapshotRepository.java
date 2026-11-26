package com.pucrs.monitorieducar.adapter.out.persistence;

import com.pucrs.monitorieducar.domain.model.RegistroSnapshot;
import com.pucrs.monitorieducar.domain.port.out.SnapshotRepositoryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSnapshotRepository implements SnapshotRepositoryPort {

    private final JdbcClient jdbc;

    public JdbcSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> buscarHash(long turmaId) {
        return jdbc.sql("select hash from turma_snapshot where turma_id = :id")
                .param("id", turmaId).query(String.class).optional();
    }

    @Override
    public void salvar(long turmaId, String hash, String nome, boolean ativa) {
        jdbc.sql("""
                insert into turma_snapshot (turma_id, hash, nome, ativa, versao_snapshot, ultima_publicacao)
                values (:id, :hash, :nome, :ativa, 1, now())
                on conflict (turma_id) do update
                   set hash = excluded.hash, nome = excluded.nome, ativa = excluded.ativa,
                       versao_snapshot = turma_snapshot.versao_snapshot + 1, ultima_publicacao = now()
                """)
                .param("id", turmaId).param("hash", hash).param("nome", nome).param("ativa", ativa).update();
    }

    @Override
    public List<RegistroSnapshot> listar() {
        return jdbc.sql("select turma_id, hash, nome, ativa, versao_snapshot, ultima_publicacao "
                        + "from turma_snapshot order by turma_id")
                .query((rs, n) -> new RegistroSnapshot(rs.getLong("turma_id"), rs.getString("hash"),
                        rs.getString("nome"), rs.getBoolean("ativa"), rs.getInt("versao_snapshot"),
                        rs.getObject("ultima_publicacao", OffsetDateTime.class)))
                .list();
    }
}
