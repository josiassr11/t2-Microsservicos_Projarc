# ADR 0002 — Comunicação assíncrona por mensagens com RabbitMQ

- **Status:** aceita
- **Data:** 2026-11-26
- **Contexto da disciplina:** aula *Mensageria e Comunicação Assíncrona*.

## Contexto

Os três serviços precisam cooperar para levar uma mudança do iEducar até o Moodle. O kickoff exige ao menos um fluxo
assíncrono com *message broker* e **eventos com nome, formato e versão documentados**, consumidores **idempotentes** e
mensagens problemáticas indo para uma **dead-letter queue**.

A comunicação síncrona (REST encadeado) criaria **acoplamento temporal**: o monitor só avançaria se o sincronizador e o
publicador (e o Moodle) estivessem no ar ao mesmo tempo, e uma lentidão do Moodle voltaria em cascata até o monitor.

## Decisão

Usar **RabbitMQ 4** com três tipos de mensagem, cada um com `tipo`, `versao` e envelope comum (`docs/contratos.md`):

| Mensagem | Natureza | De → Para | Exchange (tipo) / routing key |
|---|---|---|---|
| `TurmaAlterada.v1` | **Evento** (fato ocorrido), **completo** | monitor → sincronizador | `ieducar.eventos` (topic) / `turma.alterada` |
| `SincronizarTurma.v1` | **Comando** (pedido de ação) | sincronizador → publicador | `sincronizacao.comandos` (direct) / `sincronizar.turma` |
| `TurmaSincronizada.v1` | **Evento de resultado** | publicador → sincronizador | `moodle.eventos` (topic) / `turma.sincronizada` |

Decisões de projeto:

1. **Evento completo, não "só ID".** `TurmaAlterada` carrega a turma, os alunos e os professores. O consumidor não precisa
   chamar o iEducar de volta (evita acoplamento síncrono oculto e uma segunda dependência do iEducar); o custo é uma
   mensagem maior e a necessidade de versionar o conteúdo.
2. **Comando separado do evento.** O *evento* diz o que aconteceu no iEducar; o *comando* pede ao publicador que o Moodle
   fique igual. A decisão "quando mandar sincronizar" pertence ao sincronizador (dono do estado).
3. **Entrega garantida (pelo menos uma vez) + consumidor idempotente.** Filas e mensagens duráveis
   (`deliveryMode=PERSISTENT`), **publisher confirm** (`publisher-confirm-type=correlated`) e **`mandatory=true` +
   returns**: o produtor só considera publicado quando o broker confirma **e** a mensagem foi roteada para alguma fila;
   mensagem sem fila (*unroutable*) é tratada como falha de publicação. Do lado do consumidor, **ack só depois de
   concluir** (ack automático do listener quando o método retorna); se o serviço cair antes, a mensagem volta.
   Como reentrega pode duplicar, todo consumidor é idempotente:
   - `sincronizador`: tabela `evento_processado(event_id PK)` gravada **na mesma transação** do efeito
     (`INSERT ... ON CONFLICT DO NOTHING`; 0 linhas = duplicata → log `[IDEMPOTENCIA]`).
   - `publicador-moodle`: inbox `comando_processado(command_id PK)`; duplicata reemite o resultado já salvo e não toca
     no Moodle. Além disso o Moodle é sempre consultado por `idnumber` antes de criar (idempotência no destino).
4. **Identificadores determinísticos** para que a reentrega gere o **mesmo** id: `commandId =
   UUID.nameUUIDFromBytes("cmd:" + eventIdDoEvento)`; resultado SUCESSO/PARCIAL = `UUID.nameUUIDFromBytes("res:" +
   commandId + ":" + status)`.
5. **Versionamento `.v1` e consumidor tolerante.** O `type` AMQP é `"<tipo>.v<versao>"`. Consumidores ignoram campos
   desconhecidos (leitura tolerante); **adicionar campo opcional é mudança compatível** e permanece `.v1`; remover/renomear
   campo ou mudar o significado é **mudança incompatível** → publicar `.v2` (com novo `type`) e manter a `.v1` durante a
   migração.
6. **Contexto de rastreio:** `correlationId` nasce na varredura e é copiado em todas as mensagens; `traceparent`
   (OpenTelemetry) viaja nas propriedades AMQP (observation habilitada em `RabbitTemplate` e listeners).

### Bug encontrado nos testes e corrigido (idempotência vs. resultado FALHA)

Na primeira versão, o `eventId` do resultado era `UUID(“res:” + commandId)` **para qualquer status**. No cenário "Moodle
fora do ar": o comando esgotava as tentativas → o publicador emitia `TurmaSincronizada` com `status=FALHA`; depois do
`POST /api/dlq/reprocessar` o mesmo `commandId` dava certo e emitia `SUCESSO` com **o mesmo `eventId`** — e o
sincronizador descartava o sucesso como *duplicata* do FALHA, deixando a turma presa em `FALHA`.
Correção (`docs/contratos.md` §3.3, teste `EventIdResultadoTest`): SUCESSO/PARCIAL usam id determinístico **incluindo o
status**; **FALHA usa UUID novo a cada emissão**, porque cada falha é um fato novo. Lição para o relatório: a chave de
idempotência precisa identificar o *fato*, não só o pedido que o originou.

## Alternativas consideradas

- **REST síncrono entre os serviços.** Simples, mas gera acoplamento temporal e cascata de falhas. *Rejeitada* para o
  caminho principal. (O monitor e o publicador ainda usam HTTP **síncrono** — mas só nas bordas, contra os sistemas
  externos iEducar/Moodle, que só oferecem HTTP; ali usamos timeout/retry/circuit breaker — ADR 0003.)
- **Kafka.** Log distribuído com reprocessamento por *offset*, excelente para alto volume e replay. *Rejeitado:* a
  carga é pequena, precisamos de filas com TTL/DLQ prontas (retry por fila) e de operação simples no `docker compose`.
- **Evento só com ID** (a turma é buscada de volta no iEducar). *Rejeitada:* reintroduz dependência síncrona do
  iEducar em cada consumidor e piora o cenário "iEducar fora do ar".
- **Banco compartilhado / tabela de integração.** *Rejeitada:* quebra a posse do estado e o quantum.
- **Outbox transacional no monitor.** Seria mais forte (atomicidade snapshot+publicação). Em vez disso usamos
  "publica, espera o *confirm*, só então atualiza o snapshot": uma falha entre os dois passos **republica** (evento
  duplicado), tratada pela idempotência do consumidor. Mais simples e suficiente aqui.

## Consequências / trade-offs

- (+) Serviços desacoplados no tempo: o RabbitMQ segura as mensagens enquanto um consumidor está parado.
- (+) Cada mensagem tem dono, nome, versão e exemplo documentado (`docs/architecture.md`, catálogo).
- (−) Consistência **eventual** e mais peças para operar (broker, filas, DLQs).
- (−) Entrega "pelo menos uma vez" obriga a idempotência em todo consumidor e a escolher bem as chaves
  (ver o bug acima).
- (−) Ordenação: sem garantia global; a `versao` por turma (contador do sincronizador) permite descartar comandos
  obsoletos e ignorar resultados antigos.
