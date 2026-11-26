-- Banco PRÓPRIO do monitor-ieducar (nenhum outro serviço acessa estas tabelas).

-- O que o monitor já publicou de cada turma. O hash resume turma + alunos + professores.
create table turma_snapshot (
    turma_id          bigint       primary key,
    hash              varchar(64)  not null,
    nome              text,
    ativa             boolean      not null,
    versao_snapshot   integer      not null default 1,
    ultima_publicacao timestamptz  not null default now()
);

-- Histórico das varreduras (para mostrar o que o monitor fez e quando).
create table varredura (
    id                 bigserial    primary key,
    correlation_id     varchar(36)  not null,
    inicio             timestamptz  not null,
    fim                timestamptz,
    tipo               varchar(20)  not null,
    status             varchar(20)  not null,
    turmas_lidas       integer      not null default 0,
    eventos_publicados integer      not null default 0,
    erro               text
);
