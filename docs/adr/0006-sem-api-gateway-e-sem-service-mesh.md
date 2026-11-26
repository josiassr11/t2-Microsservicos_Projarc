# ADR 0006 — Sem API Gateway e sem Service Mesh

- **Status:** aceita
- **Data:** 2026-11-26
- **Contexto da disciplina:** aula *API Gateway e Service Mesh*.

## Contexto

Na aula, o **API Gateway** (e o **BFF**) resolve o *problema do cliente*: tráfego **norte-sul** (clientes externos →
sistema), com roteamento, autenticação, agregação de respostas e *rate limit* em um ponto de entrada. O **service mesh**
(sidecars/proxies como Istio/Linkerd) trata o tráfego **leste-oeste** (serviço ↔ serviço): mTLS, *retries*, *circuit
breaking* e telemetria sem mudar o código — com custo operacional relevante.

Precisamos decidir se algum dos dois cabe no T2.

## Decisão

**Não adotar API Gateway nem service mesh.**

- **Não há cliente externo consumindo os serviços.** A integração é *backend-to-backend* e assíncrona: o monitor
  dispara sozinho (agendador) e os serviços conversam pelo RabbitMQ. As APIs HTTP dos serviços (`/api/varreduras`,
  `/api/estado`, `/api/dlq/...`) são **operacionais**, para demonstração e administração em ambiente local; não são uma
  superfície pública que precise de agregação, autenticação por cliente ou BFF.
- **Só três serviços.** Não existe "malha" para gerenciar: o ganho de centralizar mTLS/retries/telemetria num *sidecar* é
  pequeno frente ao custo de operar Istio/Linkerd (Kubernetes, *control plane*, sidecars).
- **O mesh não cobriria o caminho principal.** O tráfego entre os serviços **passa pelo broker** (AMQP); a maioria dos
  meshes opera em HTTP/gRPC e não enxerga o tráfego de fila. Já as chamadas HTTP que existem (monitor → iEducar,
  publicador → Moodle) vão para **sistemas externos fora do mesh**, então os sidecars também não os protegeriam.
- **As preocupações do mesh já estão no código**, de forma explícita e demonstrável: timeout, retry com backoff e
  circuit breaker (Resilience4j, ADR 0003), métricas (Micrometer/Prometheus) e tracing (OpenTelemetry/Jaeger).
- O kickoff pede `docker compose up`; Compose não tem mesh.

## Alternativas consideradas

- **API Gateway (ex.: Spring Cloud Gateway, Kong).** Útil se existisse um front-end ou consumidores externos. Sem eles,
  seria só um proxy sem função. *Rejeitado.*
- **BFF para um painel de operação** (agregando `/api/estado`, `/api/comandos` etc.). Seria razoável como evolução; para
  o T2 o Grafana, o Jaeger e o painel do RabbitMQ já cumprem o papel de "painel".
- **Service mesh (Istio/Linkerd).** *Rejeitado*, pelos motivos acima (poucos serviços, broker fora do alcance, custo).

## Consequências e quando reavaliar

- (+) Menos componentes, menos latência, ambiente simples de subir e depurar.
- (−) Resiliência e telemetria são responsabilidade de **cada serviço** (duplicação de configuração). Aceitável com 3
  serviços.
- (−) As APIs operacionais ficam sem autenticação (aceitável em ambiente local/acadêmico; **não** é para produção).
- **Reavaliar** se surgirem: (a) um **front-end** ou clientes externos (→ API Gateway, possivelmente BFF por tipo de
  cliente — norte-sul); (b) **dezenas de serviços** com chamadas HTTP/gRPC diretas entre si, exigência de **mTLS** e
  política uniforme de retry/timeouts (→ service mesh, leste-oeste); (c) necessidade de autenticação/*rate limit*
  centralizados nas APIs administrativas.
