-- Dados DESTE serviço (ninguém mais acessa este banco).

-- Idempotência: cada evento/resultado processado é registrado aqui, na mesma transação do efeito.
CREATE TABLE evento_processado (
    event_id       UUID PRIMARY KEY,
    tipo           VARCHAR(60) NOT NULL,
    correlation_id VARCHAR(64),
    processado_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Estado da integração por turma do iEducar.
CREATE TABLE turma_sincronizacao (
    turma_id            BIGINT PRIMARY KEY,
    curso_ieducar_id    BIGINT,
    nome                TEXT,
    versao              INT NOT NULL,
    estado              VARCHAR(20) NOT NULL,
    hash                VARCHAR(64),
    ultimo_evento_id    UUID,
    curso_moodle_id     BIGINT,
    categoria_moodle_id BIGINT,
    ultimo_resumo       TEXT,
    ultimo_erro         TEXT,
    atualizado_em       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Mapeamento iEducar <-> Moodle (aprendido a partir dos resultados do publicador-moodle).
CREATE TABLE mapeamento (
    tipo          VARCHAR(20) NOT NULL,
    ieducar_id    BIGINT NOT NULL,
    moodle_id     BIGINT NOT NULL,
    idnumber      VARCHAR(100),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tipo, ieducar_id)
);
