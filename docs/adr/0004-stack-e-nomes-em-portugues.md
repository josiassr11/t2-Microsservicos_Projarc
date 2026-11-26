# ADR 0004 — Stack tecnológica e nomes em português

- **Status:** aceita
- **Data:** 2026-11-26

## Contexto

O kickoff deixa a stack livre desde que justificada em ADR, exige que **tudo suba com um único `docker compose up`**
(serviços, broker, bancos e iEducar/Moodle ou simuladores) e valoriza observabilidade (logs estruturados com
correlation ID, health checks, métricas, tracing desejável). O T1 já estava em Java/Spring Boot e o código dos clientes
do iEducar e do Moodle podia ser o ponto de partida.

## Decisão

| Tema | Escolha | Motivo |
|---|---|---|
| Linguagem/plataforma | **Java 17 + Spring Boot 4.1.1** (Jackson 3, `tools.jackson.*`), Maven | Reaproveita os clientes validados do T1; equipe domina |
| Mensageria | **Spring AMQP + RabbitMQ 4** (management + plugin Prometheus) | ADR 0002 |
| Persistência | **Spring JDBC (`JdbcClient`) + Flyway**, **PostgreSQL 17** (um por serviço) | SQL explícito, `INSERT ... ON CONFLICT`, migrações versionadas |
| Resiliência | **Resilience4j programático** (`resilience4j-circuitbreaker`, `-retry`, `-micrometer` 2.3.0) | ADR 0003 |
| Observabilidade | Actuator (`/actuator/health`, `/actuator/prometheus`), **Micrometer → Prometheus → Grafana**; **OpenTelemetry (starter do Boot) → Jaeger** (OTLP/HTTP) | Métricas e tracing sem código proprietário |
| Logs | Texto didático por padrão; `LOG_FORMATO=json` (`logging.structured.format.console=logstash`) | Mostrar a conversa entre serviços ao professor |
| Testes | JUnit 5, fakes em memória para as portas (sem Docker) | Rápidos e determinísticos |
| Arquitetura interna | **Hexagonal** por serviço (`domain` / `application` / `adapter.in|out` / `config`) | Mesmo estilo do T1; adaptadores trocáveis (ex.: fake Moodle nos testes) |
| Reuso | **Sem código compartilhado** entre serviços; só o contrato (`docs/contratos.md`) | Evita acoplamento por biblioteca (um deploy forçando outro) |

### Mudança em relação ao plano inicial: JPA → JDBC

O plano inicial citava **Spring Data JPA + Flyway**. Trocamos por **`JdbcClient` + SQL explícito** porque:
(1) a idempotência depende de `INSERT ... ON CONFLICT DO NOTHING` e de *upserts* — expressivos em SQL, desajeitados
em JPA; (2) as tabelas são pequenas e sem relacionamentos complexos (não há ganho com mapeamento objeto-relacional);
(3) menos "mágica" (sessão, *flush*, *lazy*) em um código que precisa ser explicado em apresentação; (4) a atomicidade
"efeito + registro do `event_id`" fica visível na transação. O Flyway permaneceu.

### Nomes em português

Serviços (`monitor-ieducar`, `sincronizador`, `publicador-moodle`), mensagens (`TurmaAlterada`, `SincronizarTurma`,
`TurmaSincronizada`), campos, tabelas, filas e logs estão em **português**. Motivo: o domínio do problema (iEducar:
turma, matrícula, servidor, enturmação) é em português e já era o vocabulário do T1 — usar uma **linguagem ubíqua** única
entre código, banco, contrato e documentação evita tradução mental (`Turma` ↔ `Class`/`Course`) e erros de mapeamento. A
convenção do ecossistema (APIs de plataforma e termos como `topic`, `exchange`, `circuit breaker`, `inbox`) permanece em
inglês onde é termo técnico consagrado. Trade-off aceito: parte da comunidade prefere inglês em código; para este
projeto, acadêmico e com domínio brasileiro, a consistência pesa mais.

## Alternativas consideradas

- **NestJS/Node** (exemplos da disciplina): válido, mas obrigaria reescrever e revalidar os clientes do T1.
- **Kafka** em vez de RabbitMQ: ADR 0002.
- **JPA/Hibernate:** ver acima.
- **Spring Cloud / Resilience4j por starter / Spring Cloud Stream:** mais "automático", porém esconde o fluxo; o código
  programático é mais fácil de explicar.
- **Biblioteca compartilhada com o envelope e os DTOs:** menos duplicação, mas acopla o ciclo de vida dos serviços
  (um deploy forçaria o outro). Duplicar ~100 linhas de envelope é o preço de manter os quanta independentes.
- **Docker Compose × Kubernetes:** o enunciado pede `docker compose up`.

## Consequências

- (+) Uma stack só, simples de subir, explicar e testar; observabilidade completa (logs, métricas, traces).
- (−) Duplicação intencional de pequenas classes (envelope, parse, modelos) entre os serviços.
- (−) Spring Boot 4 é recente; alguns utilitários (ex.: `spring-retry`) não foram adotados — daí o retry manual no
  sincronizador (ADR 0003).
