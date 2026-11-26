-- Inbox do publicador-moodle: comandos já concluídos (idempotência) e o resultado devolvido ao sincronizador.
CREATE TABLE comando_processado (
    command_id    UUID         PRIMARY KEY,
    turma_id      BIGINT       NOT NULL,
    turma_versao  INTEGER      NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    resultado     TEXT         NOT NULL,
    processado_em TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_comando_processado_turma ON comando_processado (turma_id, turma_versao);
