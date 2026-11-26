# ADR 0005 — Um banco por serviço (posse do estado) e consistência eventual

- **Status:** aceita
- **Data:** 2026-11-26
- **Contexto da disciplina:** *state ownership*, *architecture quantum*, Lei de Constantine.

## Contexto

O kickoff exige que **cada serviço seja dono dos próprios dados** e que **nenhum serviço acesse diretamente o banco de
outro**. No T1 havia uma só aplicação sem banco próprio (o estado vivia nos próprios sistemas, e a idempotência vinha do
`idnumber` no Moodle).

## Decisão

Três instâncias de **PostgreSQL 17** independentes (`monitor-db`, `sincronizador-db`, `publicador-db`), cada uma com
usuário/senha e banco próprios, esquema versionado por Flyway e **acessada só pelo seu serviço**. Não há *schemas*
compartilhados, *views* cruzadas nem *links* entre bancos.

| Serviço | Tabelas | O que é "verdade" aqui (dono) |
|---|---|---|
| `monitor-ieducar` | `turma_snapshot(turma_id, hash, nome, ativa, versao_snapshot, ultima_publicacao)`, `varredura(...)` | *O que já foi anunciado* de cada turma (hash de turma + alunos + professores) e o histórico das varreduras |
| `sincronizador` | `evento_processado(event_id)`, `turma_sincronizacao(turma_id, versao, estado, hash, curso_moodle_id, categoria_moodle_id, ultimo_resumo, ultimo_erro, ...)`, `mapeamento(tipo, ieducar_id, moodle_id, idnumber)` | O **mapeamento iEducar↔Moodle** e o **estado da integração** de cada turma |
| `publicador-moodle` | `comando_processado(command_id, turma_id, turma_versao, status, resultado)` | Quais comandos já foram executados no Moodle (inbox/idempotência) e o resultado devolvido |

Consequências diretas da posse do estado:

- **O mapeamento vive no sincronizador.** Ele o *aprende* a partir do campo `mapeamentos` de `TurmaSincronizada`
  (CATEGORIA, CURSO, ALUNO, PROFESSOR com `ieducarId`, `moodleId` e `idnumber`). O publicador **não** precisa dele
  para trabalhar: calcula o `idnumber` (`IEDUCAR-TURMA-<id>` etc.) por regra determinística e consulta o próprio Moodle.
- Quem precisa de um dado de outro serviço **pede por mensagem** (evento completo, ADR 0002) em vez de consultar o banco.
- Cada banco pode ser trocado, migrado ou escalado sem avisar os demais — cada serviço é um **quantum**.

### Consistência eventual e o estado visível

Como o Moodle só é atualizado depois de duas mensagens, existe uma janela em que o iEducar já mudou e o Moodle ainda
não. Em vez de esconder isso, o sincronizador **expõe o estado** da turma em `GET /api/estado` (e `/{turmaId}`):

```
ENVIADA ──(TurmaSincronizada SUCESSO)──▶ SINCRONIZADA
   │ ├───(TurmaSincronizada PARCIAL)───▶ PARCIAL      (alguns itens falharam; ver ultimoErro)
   │ └───(TurmaSincronizada FALHA)─────▶ FALHA        (esgotou tentativas; comando na DLQ)
   └── nova mudança no iEducar ─▶ versao+1, volta a ENVIADA
```

Regras que mantêm o estado correto mesmo com mensagens fora de ordem ou repetidas: cada evento aumenta a `versao`
da turma; um resultado com `versao` **menor** que a atual é só registrado como obsoleto; o publicador descarta comandos
de versão menor que a já aplicada; `event_id` duplicado é ignorado.

## Alternativas consideradas

- **Banco único com tabelas separadas por serviço** (um Postgres, vários *schemas*). Mais barato, mas permite acesso
  indevido por engano e faz do banco um ponto único de falha/implantação conjunta (um só quantum). *Rejeitada.*
- **Compartilhar o estado numa "tabela de integração".** Quebra o requisito do kickoff e a posse do estado.
- **Serviço de mapeamento separado.** Mais coesão "pura", porém mais conversa por mensagem para algo que muda sempre
  junto com o estado da turma (ADR 0001).
- **Guardar o mapeamento só no Moodle** (como o T1 fazia via `idnumber`). Continua válido como idempotência no destino,
  mas deixa o sincronizador "cego" sobre o que já foi criado; por isso o mapeamento explícito.

## Consequências / trade-offs

- (+) Isolamento: falha ou lentidão do banco de um serviço não afeta os outros.
- (+) O estado por turma permite *dizer* o que está acontecendo (útil na demonstração e na operação).
- (−) Consistência eventual, **sem transações distribuídas**; a coerência vem de idempotência + versões + reprocesso.
- (−) Três bancos para operar/backup; consultas "juntando tudo" exigem chamadas às APIs de cada serviço.
