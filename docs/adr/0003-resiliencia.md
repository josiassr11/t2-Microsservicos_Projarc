# ADR 0003 — Resiliência: timeout, retry com backoff, circuit breaker, filas de retry e DLQ

- **Status:** aceita
- **Data:** 2026-11-26
- **Contexto da disciplina:** aulas de Mensageria (entrega garantida, DLQ) e Introdução aos Microsserviços (falhas parciais).

## Contexto

O kickoff exige: chamadas ao iEducar e ao Moodle com **timeout e retry com backoff** (circuit breaker desejável),
consumidores **idempotentes** e mensagens que falham repetidamente indo para uma **dead-letter queue** sem bloquear o
restante do fluxo; e quatro cenários de falha respondidos pela arquitetura (iEducar fora, Moodle fora, Moodle cria o curso
mas a resposta não chega, serviço reinicia no meio da sincronização).

Cada técnica resolve um problema diferente; o ponto da decisão é **onde** aplicar cada uma.

## Decisão

| Técnica | Onde | Parâmetros padrão (variáveis `RESILIENCIA_*`) | Problema que resolve |
|---|---|---|---|
| **Timeout** | HTTP para iEducar (monitor) e Moodle (publicador) | conexão 3 s, leitura 10 s | Não ficar pendurado em sistema lento |
| **Retry com backoff exponencial** (dentro da chamada) | idem — **exceto nas chamadas de criação do Moodle** (`criarCategoria`, `criarCurso`, `criarUsuario`), que só têm timeout + circuit breaker (ver "Decisão: criação não é repetida na chamada") | 3 tentativas, 0,5 s ×2 (0,5 s → 1 s) — só para `SistemaIndisponivelException` (rede, timeout, 5xx, token recusado) | Falhas **transitórias curtas** em operações idempotentes (leituras, atualizações, inscrições) |
| **Circuit breaker** (Resilience4j) | idem | janela 6 chamadas, mínimo 4, falha ≥ 50 %, aberto 20 s, 2 chamadas em meio-aberto | Parar de martelar um sistema fora do ar; falhar **na hora** (sem esperar timeout) |
| **Filas de retry** (retry de **mensagem**) | publicador-moodle | `retry-5s` e `retry-30s` (TTL + dead-letter para a fila principal) | Falhas que duram mais que o retry em memória |
| **DLQ** + reprocesso manual | publicador (`publicador-moodle.comandos.dlq`), sincronizador (`*.dlq`) | `POST /api/dlq/reprocessar` | Mensagem que não deu certo após as tentativas, ou **veneno** |
| **Idempotência** | consumidores | `event_id` / `command_id` + busca por `idnumber` | Reentrega e reprocesso sem duplicar |
| **Confirm + mandatory** | publicação | timeout do confirm 5 s | Não perder mensagem sem perceber |

### Cenário do publicador (Moodle fora)

1. Chamada ao Moodle falha → **retry em memória** (0,5 s, 1 s; só em leituras/atualizações/inscrições) → o circuito abre (`CLOSED → OPEN`), chamadas seguintes
   falham na hora com "circuito aberto".
2. `SistemaIndisponivelException` sobe para `ProcessadorComando`, que aplica o **retry por fila** com o header
   `x-tentativa`: tentativa 1 → republica em `retry-5s`; 2 → `retry-30s`; 3 → `dlq` **e** publica `TurmaSincronizada` com
   `status=FALHA` (o sincronizador passa a turma para `FALHA`, visível em `/api/estado`).
3. Com o Moodle de volta, `POST /api/dlq/reprocessar` devolve a mensagem para a fila principal (`x-tentativa=1`). Se o
   circuito ainda estiver aberto (~20 s após a última falha), o comando espera nas filas de retry e é aplicado quando o
   circuito fecha (`OPEN → HALF_OPEN → CLOSED`) → resultado `SUCESSO` → turma `SINCRONIZADA`.

Falha de **negócio** do Moodle em um item (`MoodleException`, ex.: e-mail recusado) **não** faz retry de mensagem: o
item entra em `erros` e o status do resultado é `PARCIAL` (falha de negócio ao criar a categoria/curso da turma vira
`FALHA` sem retry, pois repetir não mudaria o resultado).

### Decisão: criação não é repetida na chamada (o "pior caso de rede")

Numa chamada de rede existe um resultado que o cliente **não consegue distinguir** de uma falha comum: o servidor **executou** a
operação, mas a resposta não voltou (timeout, conexão derrubada). Para uma **leitura**, uma **atualização** (`atualizarCurso`),
ou uma **inscrição** (`inscrever`, idempotente) repetir é inofensivo. Para uma **criação** não: um segundo `criarCurso` seria
recusado como duplicado (erro de negócio, que virava `FALHA`/`PARCIAL` sem retry de mensagem) ou, em outro sistema, criaria
um segundo recurso. Esse era o cenário 3 do kickoff (*o Moodle cria o curso, mas a resposta não chega*).

**Decisão.** `criarCategoria`, `criarCurso` e `criarUsuario` passam **só por timeout e circuit breaker** — não têm retry dentro da
chamada. Se falham de forma ambígua, a falha sobe como `SistemaIndisponivelException` e cai no **retry por fila**
(`retry-5s` → `retry-30s` → DLQ). Quando o comando é reentregue, o fluxo **recomeça pela busca por `idnumber`**: o recurso que o
Moodle criou "às escuras" é encontrado e o processamento segue dali. O retry de mensagem é, portanto, o único lugar onde uma
criação é refeita, e ele é seguro porque o primeiro passo é sempre "existe?". Falha de criação ainda conta no circuit
breaker (quatro seguidas abrem o circuito).

Reproduzível com o script `3-resposta-perdida` (injeção `perderRespostaNaCriacao`: a criação é executada de verdade e, em
seguida, a resposta é "perdida"); coberto por `MoodleResilienteTest` e `ConvergenciaAposRespostaPerdidaTest`.

### Por que dois estilos de retry de mensagem (publicador × sincronizador)?

- **Publicador:** a falha é **externa e demorada** (Moodle fora por minutos). Retry imediato só gastaria tentativas;
  queremos **esperar** (5 s, 30 s) sem bloquear a fila principal nem o *prefetch* do consumidor. Por isso o retry é
  feito **republicando** em filas com TTL (*delay queues*), o que também permite observar a mensagem esperando no painel
  do RabbitMQ.
- **Sincronizador:** a falha típica é **local e curta** (banco/broker momentaneamente indisponível, publicação sem
  confirm). Basta repetir **no próprio consumidor** (3 tentativas, 1 s — `PoliticaDeTentativas`) dentro da transação e,
  esgotado, rejeitar **sem requeue** → o broker envia para a DLQ via `sincronizador.dlx`. Mensagem **ilegível**
  (*poison*) vai **direto** para a DLQ (`AmqpRejectAndDontRequeueException`), sem tentativas inúteis. O retry foi
  implementado manualmente (e não pela propriedade `spring.rabbitmq.listener.simple.retry`) para não depender do
  `spring-retry` no Spring Boot 4 e para tratar o veneno como caso à parte.

### Monitor (iEducar fora)

O monitor **lê tudo primeiro**; se o iEducar falhar (esgotados timeout/retry/circuito), a varredura é abortada com
status `FALHA` e **nada é publicado** — sem dados confiáveis não se emite evento. A próxima varredura tenta de novo.
Falha de **publicação** (RabbitMQ fora, mensagem não roteada) **não atualiza o snapshot** da turma: a mudança continua
pendente e é republicada na varredura seguinte (status `PARCIAL`).

## Alternativas consideradas

- **Só retry em memória.** Não cobre indisponibilidades longas e segura a thread do consumidor. *Rejeitada.*
- **Só `requeue` imediato no broker.** Cria laço quente (a mesma mensagem volta em milissegundos). *Rejeitada.*
- **Plugin de mensagem atrasada (`rabbitmq-delayed-message-exchange`).** Funcionaria, mas exige plugin extra; filas com
  TTL + DLX são nativas.
- **Circuit breaker via starter do Spring Boot / anotações.** Preferimos o uso **programático** do Resilience4j: o fluxo
  fica explícito no código e nos logs (`[HTTP-OUT]`, `[RETRY]`, `[CIRCUITO]`).

## Consequências / limitações conhecidas

- (+) Os quatro cenários do kickoff são reproduzíveis por script (`scripts/cenarios/1-*` a `4-*`) e visíveis em logs,
  filas do RabbitMQ, painel do Grafana e traces.
- (+) Duplicatas vindas de reentrega (ex.: serviço cai depois de executar e antes do ack — cenário 4 do kickoff, `4-reinicio-no-meio`) são
  absorvidas pelo inbox/`event_id` e pela busca por `idnumber`.
- (−) O reprocesso da DLQ é **manual** (por decisão: uma falha repetida precisa de olhar humano antes de reenfileirar).
- (+) O cenário 3 do kickoff (resposta perdida após criar) converge sem duplicar, graças à decisão acima.
- (−) A injeção de falhas (`indisponivel`, `perderRespostaNaCriacao`, `latenciaMs`) vive só em memória e só existe com
  `FALHA_INJETAVEL=true`; é ferramenta de demonstração, não de produção.
- (−) O bug do `eventId` de resultado (ADR 0002) mostrou que combinar **idempotência** com **reprocesso** exige cuidado
  na escolha das chaves; está coberto por teste unitário.
