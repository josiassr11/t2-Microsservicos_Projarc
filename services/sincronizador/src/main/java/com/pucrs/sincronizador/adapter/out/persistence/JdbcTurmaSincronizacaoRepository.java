package com.pucrs.sincronizador.adapter.out.persistence;

import com.pucrs.sincronizador.domain.model.EstadoSincronizacao;
import com.pucrs.sincronizador.domain.model.Resumo;
import com.pucrs.sincronizador.domain.model.TurmaInfo;
import com.pucrs.sincronizador.domain.model.TurmaSincronizacao;
import com.pucrs.sincronizador.domain.port.out.TurmaSincronizacaoRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTurmaSincronizacaoRepository implements TurmaSincronizacaoRepository {

    private static final String COLUNAS = """
            turma_id, curso_ieducar_id, nome, versao, estado, hash, ultimo_evento_id, curso_moodle_id,
            categoria_moodle_id, ultimo_resumo, ultimo_erro, atualizado_em""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcTurmaSincronizacaoRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public int registrarEnvio(TurmaInfo turma, String hash, UUID eventId) {
        return jdbc.sql("""
                        INSERT INTO turma_sincronizacao
                            (turma_id, curso_ieducar_id, nome, versao, estado, hash, ultimo_evento_id, atualizado_em)
                        VALUES (:id, :curso, :nome, 1, 'ENVIADA', :hash, :evento, now())
                        ON CONFLICT (turma_id) DO UPDATE
                           SET curso_ieducar_id = EXCLUDED.curso_ieducar_id,
                               nome = EXCLUDED.nome,
                               versao = turma_sincronizacao.versao + 1,
                               estado = 'ENVIADA',
                               hash = EXCLUDED.hash,
                               ultimo_evento_id = EXCLUDED.ultimo_evento_id,
                               atualizado_em = now()
                        RETURNING versao
                        """)
                .param("id", turma.id()).param("curso", turma.cursoId()).param("nome", turma.nome())
                .param("hash", hash).param("evento", eventId)
                .query(Integer.class).single();
    }

    @Override
    public boolean registrarResultado(long turmaId, int versao, EstadoSincronizacao estado, Long categoriaMoodleId,
                                      Long cursoMoodleId, Resumo resumo, String erro, UUID eventId) {
        int linhas = jdbc.sql("""
                        INSERT INTO turma_sincronizacao
                            (turma_id, versao, estado, curso_moodle_id, categoria_moodle_id, ultimo_resumo, ultimo_erro, atualizado_em)
                        VALUES (:id, :versao, :estado, :curso, :categoria, :resumo, :erro, now())
                        ON CONFLICT (turma_id) DO UPDATE
                           SET versao = EXCLUDED.versao,
                               estado = EXCLUDED.estado,
                               curso_moodle_id = COALESCE(EXCLUDED.curso_moodle_id, turma_sincronizacao.curso_moodle_id),
                               categoria_moodle_id = COALESCE(EXCLUDED.categoria_moodle_id, turma_sincronizacao.categoria_moodle_id),
                               ultimo_resumo = EXCLUDED.ultimo_resumo,
                               ultimo_erro = EXCLUDED.ultimo_erro,
                               atualizado_em = now()
                         WHERE turma_sincronizacao.versao <= EXCLUDED.versao
                        """)
                .param("id", turmaId).param("versao", versao).param("estado", estado.name())
                .param("curso", cursoMoodleId).param("categoria", categoriaMoodleId)
                .param("resumo", escrever(resumo)).param("erro", erro)
                .update();
        return linhas > 0;
    }

    @Override
    public Optional<TurmaSincronizacao> buscar(long turmaId) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM turma_sincronizacao WHERE turma_id = :id")
                .param("id", turmaId).query(this::linha).optional();
    }

    @Override
    public List<TurmaSincronizacao> listar() {
        return jdbc.sql("SELECT " + COLUNAS + " FROM turma_sincronizacao ORDER BY turma_id").query(this::linha).list();
    }

    private TurmaSincronizacao linha(ResultSet rs, int n) throws SQLException {
        return new TurmaSincronizacao(rs.getLong("turma_id"), (Long) rs.getObject("curso_ieducar_id"),
                rs.getString("nome"), rs.getInt("versao"), EstadoSincronizacao.valueOf(rs.getString("estado")),
                rs.getString("hash"), (UUID) rs.getObject("ultimo_evento_id"), (Long) rs.getObject("curso_moodle_id"),
                (Long) rs.getObject("categoria_moodle_id"), ler(rs.getString("ultimo_resumo")),
                rs.getString("ultimo_erro"), rs.getObject("atualizado_em", OffsetDateTime.class).toInstant());
    }

    private String escrever(Resumo resumo) {
        return json.writeValueAsString(resumo);
    }

    private Resumo ler(String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return json.readValue(texto, Resumo.class);
        } catch (JacksonException e) {
            return null;
        }
    }
}
