# Contratos entre os microsserviços

Este documento é a **fonte de verdade** dos contratos (mensagens, filas, portas, convenções). Os três serviços
são independentes: **não compartilham código**, só este contrato.

## 1. Serviços, portas e bancos

| Serviço | Capacidade | Porta no host | Banco (Postgres 17, um por serviço) |
|---|---|---|---|
| `monitor-ieducar` | Detectar mudanças no iEducar | 8091 | `monitor-db` / db `monitor` |
| `sincronizador` | Mapeamento iEducar↔Moodle e estado da integração | 8092 | `sincronizador-db` / db `sincronizador` |
| `publicador-moodle` | Aplicar no Moodle | 8093 | `publicador-db` / db `publicador` |

Pacotes Java: `com.pucrs.monitorieducar`, `com.pucrs.sincronizador`, `com.pucrs.publicadormoodle`.
Todos: Java 17, Spring Boot 4.1.1 (Jackson 3 = `tools.jackson.*`), Maven, hexagonal
(`domain` / `application` / `adapter.in` / `adapter.out` / `config`). Banco via **JdbcClient + Flyway** (SQL explícito,
`INSERT ... ON CONFLICT`). Resiliência com **Resilience4j programático** (sem starter). JSON das mensagens
manual com `JsonMapper` (tolerante a campos desconhecidos).

## 2. Envelope (corpo JSON de TODA mensagem)

```json
{
  "eventId": "uuid",
  "tipo": "TurmaAlterada",
  "versao": 1,
  "ocorridoEm": "2026-11-26T12:00:00Z",
  "correlationId": "uuid",
  "origem": "monitor-ieducar",
  "dados": { }
}
```

Propriedades AMQP: `messageId = eventId`, `type = "<tipo>.v<versao>"`, `correlationId`, `contentType = application/json`,
`deliveryMode = PERSISTENT`. O `correlationId` nasce na varredura do monitor e é **copiado** por todos os serviços
(vai no MDC do log como `correlationId`).

Consumidor **tolerante**: ignora campos desconhecidos. Mudança compatível = adicionar campo opcional (continua v1).
Mudança incompatível = nova versão (`.v2`) com novo `type`.

## 3. Mensagens

### 3.1 `TurmaAlterada.v1` (evento **completo**, monitor → sincronizador)

```json
"dados": {
  "mudanca": "CRIADA | ATUALIZADA | REPUBLICADA",
  "hash": "sha256-hex",
  "turma": { "id": 10, "nome": "1º Ano A", "ano": 2026, "ativa": true,
             "escolaId": 1, "escolaNome": "Escola X", "cursoId": 3, "cursoNome": "Ensino Fundamental" },
  "alunos":      [ { "id": 7, "nome": "Ana Silva", "email": "ana@x.com | null", "ativo": true } ],
  "professores": [ { "id": 2, "nome": "Carlos Souza", "email": null, "ativo": true } ]
}
```

### 3.2 `SincronizarTurma.v1` (**comando**, sincronizador → publicador-moodle)

`eventId` = `commandId` **determinístico**: `UUID.nameUUIDFromBytes(("cmd:" + eventIdDoEventoOrigem).getBytes(UTF_8))`
(reentrega do mesmo evento gera o mesmo comando → o publicador descarta duplicata).

```json
"dados": { "versao": 3, "hash": "…", "turma": {…igual 3.1…}, "alunos": […], "professores": […] }
```
`versao` = contador por turma mantido pelo sincronizador (incrementa a cada evento processado).

### 3.3 `TurmaSincronizada.v1` (**resultado**, publicador-moodle → sincronizador)

```json
"dados": {
  "turmaId": 10, "versao": 3,
  "status": "SUCESSO | PARCIAL | FALHA",
  "categoriaMoodleId": 4, "cursoMoodleId": 12,
  "resumo": { "cursosCriados":0, "cursosAtualizados":0, "cursosOcultados":0,
              "usuariosCriados":0, "usuariosAtualizados":0, "inscricoesCriadas":0, "inscricoesSuspensas":0 },
  "mapeamentos": [ { "tipo": "CATEGORIA | CURSO | ALUNO | PROFESSOR", "ieducarId": 3, "moodleId": 4, "idnumber": "IEDUCAR-CURSO-3" } ],
  "erros": [ "texto" ],
  "tentativas": 1
}
```
`eventId` do resultado: **SUCESSO/PARCIAL** = `UUID.nameUUIDFromBytes(("res:" + commandId + ":" + status).getBytes(UTF_8))` (reemitir o mesmo resultado é idempotente);
**FALHA** = UUID novo a cada emissão. Motivo: um comando que falhou, foi reprocessado da DLQ e deu certo emite um resultado novo para o *mesmo* `commandId`; se o id dependesse só do comando, o sincronizador descartaria o SUCESSO como "duplicata" do FALHA.

## 4. Exchanges, filas e roteamento

| Exchange | Tipo | Dono (publica) | Routing key |
|---|---|---|---|
| `ieducar.eventos` | topic | monitor-ieducar | `turma.alterada` |
| `sincronizacao.comandos` | direct | sincronizador | `sincronizar.turma` |
| `moodle.eventos` | topic | publicador-moodle | `turma.sincronizada` |

Cada **consumidor** declara a própria fila (e o exchange, de forma idempotente). Quem só publica declara apenas o exchange.
Mensagem sem fila ligada = *unroutable* → o publisher usa `mandatory=true` + confirm e trata como **falha de publicação**
(o monitor não atualiza o snapshot e republica na próxima varredura).

| Fila | Dono | Ligação | Observação |
|---|---|---|---|
| `sincronizador.turma-alterada` | sincronizador | `ieducar.eventos` / `turma.alterada` | DLX `sincronizador.dlx` → `sincronizador.turma-alterada.dlq` |
| `sincronizador.turma-sincronizada` | sincronizador | `moodle.eventos` / `turma.sincronizada` | DLX `sincronizador.dlx` → `sincronizador.turma-sincronizada.dlq` |
| `publicador-moodle.comandos` | publicador-moodle | `sincronizacao.comandos` / `sincronizar.turma` | fila principal |
| `publicador-moodle.comandos.retry-5s` | publicador-moodle | (default exchange, rk = nome da fila) | `x-message-ttl=5000`, DLX `""`, DL-rk `publicador-moodle.comandos` |
| `publicador-moodle.comandos.retry-30s` | publicador-moodle | idem | `x-message-ttl=30000`, idem |
| `publicador-moodle.comandos.dlq` | publicador-moodle | (default exchange, rk = nome da fila) | parada final; reprocesso manual |

**Política de falha**
- *sincronizador*: retry do listener (3 tentativas, backoff 1s); esgotou → rejeita sem requeue → DLQ. Mensagem ilegível (poison) → `AmqpRejectAndDontRequeueException` direto → DLQ.
- *publicador-moodle*: falha **transitória** (Moodle indisponível / circuito aberto) → republica o comando com header `x-tentativa` incrementado em `retry-5s` (tentativa 1) ou `retry-30s` (tentativa 2); tentativa 3 → `dlq` + publica `TurmaSincronizada` com `status=FALHA`. Falha de **negócio** do Moodle em um item → vai para `erros` e o status vira `PARCIAL` (não faz retry de mensagem).
- Ack só depois de concluir (ack automático do listener após o método retornar); publicações sempre com **publisher confirm**.

## 5. Idempotência

- Consumidores guardam o `eventId` processado (tabela com PK) na **mesma transação** do efeito; duplicata → log `[IDEMPOTENCIA]` e ack sem reprocessar.
- Moodle: sempre *buscar por `idnumber` antes de criar* (`IEDUCAR-CURSO-<id>` categoria, `IEDUCAR-TURMA-<id>` curso, `IEDUCAR-ALUNO-<id>`, `IEDUCAR-SERVIDOR-<id>`; usernames `aluno<id>` / `prof<id>`; papéis aluno=5, professor=3; nunca apaga: turma inativa oculta o curso; matrícula cancelada / vínculo removido suspende a inscrição) — mesmas regras do T1.

## 6. Esquemas de banco (Flyway `V1__*.sql`)

- **monitor**: `turma_snapshot(turma_id PK, hash, nome, ativa, versao_snapshot, ultima_publicacao)`; `varredura(id, correlation_id, inicio, fim, tipo, status, turmas_lidas, eventos_publicados, erro)`.
- **sincronizador**: `evento_processado(event_id PK, tipo, correlation_id, processado_em)`;
  `turma_sincronizacao(turma_id PK, curso_ieducar_id, nome, versao, estado, hash, ultimo_evento_id, curso_moodle_id, categoria_moodle_id, ultimo_resumo, ultimo_erro, atualizado_em)`;
  `mapeamento(tipo, ieducar_id, moodle_id, idnumber, atualizado_em, PK(tipo, ieducar_id))`.
  Estados: `ENVIADA` → `SINCRONIZADA | PARCIAL | FALHA`.
- **publicador**: `comando_processado(command_id PK, turma_id, turma_versao, status, resultado, processado_em)`.

## 7. Endpoints HTTP (além de `/actuator/health`, `/actuator/prometheus`)

- monitor (8091): `POST /api/varreduras` (incremental), `POST /api/varreduras/completa` (republica todas as turmas, `mudanca=REPUBLICADA`), `GET /api/varreduras/ultima`, `GET /api/snapshots`.
- sincronizador (8092): `GET /api/estado`, `GET /api/estado/{turmaId}`, `GET /api/mapeamentos`.
- publicador-moodle (8093): `GET /api/comandos` (últimos processados), `POST /api/dlq/reprocessar` (move DLQ → fila principal com `x-tentativa` zerado),
  `GET /api/dlq` (quantidade), e, **somente com `FALHA_INJETAVEL=true`**, `GET|POST /api/falhas/moodle` (`{"indisponivel":true|false}`) que simula Moodle fora do ar.

## 8. Logs (o professor precisa **ver** a conversa entre os serviços)

Padrão texto (default): `%d{HH:mm:ss.SSS} %-5level [<servico>] [corr=%X{correlationId:--}] %msg%n`.
`LOG_FORMATO=json` ativa o perfil `json` (`logging.structured.format.console=logstash`). Etiquetas fixas no início da mensagem:

`[VARREDURA]` `[HTTP-OUT]` (chamada a sistema externo + resposta/tempo) `[RETRY]` `[CIRCUITO]` `[PUBLICA]` (exchange, rk, tipo, eventId, confirmado em Nms)
`[RECEBE]` (fila, tipo, eventId) `[PROCESSA]` `[IDEMPOTENCIA]` `[BANCO]` `[DLQ]` `[RESULTADO]`.
Cada linha traz `turmaId`/`eventId` quando existirem. Nunca logar senha/token.

## 9. Configuração (variáveis de ambiente comuns)

`RABBITMQ_HOST=rabbitmq RABBITMQ_PORT=5672 RABBITMQ_USER=guest RABBITMQ_PASSWORD=guest`,
`DB_URL=jdbc:postgresql://<svc>-db:5432/<db> DB_USER DB_PASSWORD`, `LOG_FORMATO=texto|json`,
`OTLP_TRACING_ENDPOINT=http://jaeger:4318/v1/traces`. Específicas — monitor: `IEDUCAR_URL IEDUCAR_API_TOKEN IEDUCAR_API_ACCESS_KEY IEDUCAR_API_SECRET_KEY IEDUCAR_INSTITUICAO_ID MONITOR_INTERVALO (PT1M) MONITOR_ATRASO_INICIAL (PT20S)`;
publicador: `MOODLE_URL MOODLE_WS_TOKEN MOODLE_PAPEL_ALUNO_ID MOODLE_PAPEL_PROFESSOR_ID MOODLE_SENHA_INICIAL MOODLE_DOMINIO_EMAIL FALHA_INJETAVEL`.
Resiliência (monitor e publicador): timeout conexão 3s / leitura 10s, retry 3 tentativas backoff exponencial (500ms ×2), circuit breaker (janela 6 chamadas, mínimo 4, falha ≥50%, aberto por 20s, 2 chamadas em meio-aberto), propriedades `resiliencia.*` ajustáveis por env.
