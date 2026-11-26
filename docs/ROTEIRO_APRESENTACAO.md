# Roteiro da apresentação individual (10–15 min)

O kickoff pede uma apresentação técnica **individual**, com **demonstração ao vivo e pelo menos um cenário de falha**, e
perguntas sobre código e decisões. Este roteiro dá a ordem, o que dizer em cada passo e as perguntas mais prováveis.
Tempo total alvo: **~14 min** de apresentação, deixando espaço para perguntas.

> Dica geral: o professor avalia sobretudo as **decisões de decomposição e de comunicação**. Em cada passo da demonstração,
> diga *qual decisão* aquilo ilustra. Mostrar que funciona é o meio; explicar o porquê é o objetivo.

## Antes de entrar na sala (checklist de 10 min)

1. Docker Desktop aberto. Pare o ambiente do T1 (ele usa as mesmas portas 8080/8081).
2. Suba o T2 com os sistemas fakes (rápido, previsível): `.\scripts\subir-com-fakes.ps1`. Se quiser mostrar o iEducar e o Moodle
   **reais**, suba o modo A com antecedência (`docker compose up -d --build` + `docker compose exec ieducar demo-seed base`) — o
   boot leva vários minutos — e ensaie os dois modos.
3. Reduza o ruído: no `.env`, `MONITOR_INTERVALO=PT5M` (a varredura automática de 1 em 1 min polui os logs). Depois,
   `docker compose up -d --no-deps monitor-ieducar` para aplicar. Você dispara as varreduras manualmente.
4. Rode uma vez `.\scripts\status.ps1` e confira: serviços respondendo, DLQ vazia. Se quiser começar "do zero" na demonstração:
   `docker compose down -v` e suba de novo.
5. Organize as janelas:
   - **Terminal 1**: `.\scripts\acompanhar.ps1 -Desde 0s` (logs ao vivo, fonte grande).
   - **Terminal 2**: comandos (varredura, cenários).
   - **Navegador, abas prontas**: RabbitMQ http://localhost:15672 (guest/guest, aba *Queues* e *Exchanges*), Grafana
     http://localhost:3000/d/integracao-t2, Jaeger http://localhost:16686.
6. Tenha à mão o diagrama da seção 2 do README e a tabela de serviços.

## Roteiro minuto a minuto

### 0:00–1:00 — A arquitetura em um minuto

Mostre o diagrama do README (seção 2). Diga:

- "O T1 era um programa só. No T2 a mesma integração virou **três microsserviços** ligados por **mensagens**."
- "**monitor-ieducar** detecta mudanças; **sincronizador** guarda o estado e o mapeamento iEducar↔Moodle; **publicador-moodle**
  aplica no Moodle. Cada um tem o **seu banco**; nenhum lê o banco do outro."
- "Decompus **por capacidade de negócio**, não por entidade. Cada fronteira esconde uma decisão que muda por um motivo
  diferente: a API do iEducar, a regra de mapeamento, a API do Moodle." (ADR 0001)
- "Entre serviços, só **RabbitMQ**; HTTP só nas bordas, falando com os sistemas externos."

### 1:00–2:00 — O ambiente no ar

No terminal 2: `docker compose ps`. Aponte: três bancos, RabbitMQ, Jaeger, Prometheus, Grafana, os três serviços, tudo
`healthy`. Diga: "Tudo sobe com um `docker compose up`." (Se estiver nos fakes: "iEducar e Moodle são simuladores, como o kickoff permite; também ensaiei com os reais.")

### 2:00–3:00 — Os logs como "conversa" entre os serviços

Aponte o terminal 1. Explique o formato: `[serviço] [corr=...] [ETIQUETA] mensagem`. "O **correlationId** nasce na varredura e
acompanha a mudança por todos os serviços; as etiquetas dizem o que está acontecendo: `[HTTP-OUT]`, `[PUBLICA]`, `[RECEBE]`,
`[PROCESSA]`, `[RESULTADO]`, `[RETRY]`, `[CIRCUITO]`, `[DLQ]`, `[IDEMPOTENCIA]`."

### 3:00–6:00 — Uma sincronização completa, narrada

```powershell
Invoke-RestMethod -Method Post http://localhost:8091/api/varreduras
```

Narre as linhas que vão aparecendo (a ordem é sempre esta):

1. `monitor [VARREDURA] lendo turmas...` + `[HTTP-OUT] iEducar GET /api/school-class -> 200` — "o monitor chama o iEducar, com timeout, retry e circuit breaker."
2. `monitor [PROCESSA] turmaId=3 -> CRIADA (hash anterior=-, novo=...)` — "comparou o hash com o snapshot do seu banco; é nova."
3. `monitor [PUBLICA] ... TurmaAlterada.v1 ... confirmado pelo broker em 18ms` — "só considera entregue depois do **publisher confirm**; só então atualiza o snapshot."
4. `sincronizador [RECEBE] ... [PROCESSA] turma 3 v1 -> ENVIADA` + `[PUBLICA] SincronizarTurma.v1` — "registrou o estado e mandou um **comando** ao publicador."
5. `publicador [RECEBE] ... [HTTP-OUT] Moodle ... [RESULTADO] status=SUCESSO resumo=...` — "aplicou no Moodle: categoria, curso, usuários, inscrições."
6. `sincronizador [RECEBE] TurmaSincronizada.v1 ... ENVIADA -> SINCRONIZADA` — "o resultado volta como **evento**; o sincronizador, dono do estado, atualiza."

Depois, mostre o resultado: `.\scripts\status.ps1` (ou `curl.exe http://localhost:8092/api/estado` e `/api/mapeamentos`).
Dispare **de novo** a varredura: `publicadas=0 inalteradas=3`. "Nada mudou, nada é enviado: o monitor compara hashes. O T1 relia e
reaplicava tudo a cada ciclo."

Faça uma mudança e mostre que só ela se propaga:

```powershell
curl.exe "http://localhost:8080/__admin/renomear-turma?id=3&nome=Turma+Renomeada+ao+vivo"     # modo real: docker compose exec ieducar demo-seed nova-turma
Invoke-RestMethod -Method Post http://localhost:8091/api/varreduras                           # publicadas=1 inalteradas=2
```

### 6:00–8:00 — RabbitMQ: o que "está entre" os serviços

Abra o painel (http://localhost:15672):

- **Exchanges**: `ieducar.eventos` (topic), `sincronizacao.comandos` (direct), `moodle.eventos` (topic). "Cada serviço publica em um exchange e **não sabe quem consome**."
- **Queues**: `sincronizador.turma-alterada`, `sincronizador.turma-sincronizada`, `publicador-moodle.comandos`, e as de retry/DLQ.
  "Quem consome declara a própria fila. As filas de retry têm TTL de 5 s e 30 s e devolvem a mensagem à fila principal; a DLQ é a parada final."
- Clique em uma fila → *Bindings* e *Message rates*. Mostre que a DLQ está vazia — até o cenário de falha.

### 8:00–9:00 — Observabilidade: Grafana e Jaeger

- **Grafana** (dashboard `T2 - Integração iEducar -> Moodle`): mensagens publicadas/consumidas, estado dos circuit breakers, retries, comandos processados por resultado, varreduras por status. "Cobre mensagens processadas, falhas e tempo — o que o kickoff pede."
- **Jaeger**: *Service* `monitor-ieducar` → *Find Traces* → abra um trace. "Um único trace atravessa os **três serviços**, mesmo passando por mensagens: o contexto de tracing viaja nos headers da mensagem."
- Mencione `/actuator/health` e `/actuator/prometheus` em cada serviço.

### 9:00–12:30 — Cenários de falha (1, 2 e 3 ao vivo; o 4 se sobrar tempo; os extras só sob pergunta)

Tempo de cada um e o que provar (detalhes e logs reais em [`failure-scenarios.md`](failure-scenarios.md)). Se o tempo apertar,
faça 1 e 3 e deixe o 2 para mostrar só até a DLQ:

1. **Cenário 1 — iEducar fora (~1 min).** `.\scripts\cenarios\1-ieducar-fora.ps1`. Mostre `[RETRY] tentativa 1/3 ... 2/3` com espera
   crescente, `[CIRCUITO] CLOSED -> OPEN`, "chamada recusada sem tentar a rede", e que **nenhum `[PUBLICA]`** aparece. Depois a recuperação sozinha.
   *Decisão ilustrada: timeout + retry com backoff + circuit breaker; ler tudo antes de publicar.*
2. **Cenário 2 — Moodle fora (~2–3 min; é o mais completo).** `.\scripts\cenarios\2-moodle-fora.ps1`. Narre enquanto espera:
   `[RETRY] reenfileirado em retry-5s`, depois `retry-30s`, depois `[DLQ]` e o sincronizador em `FALHA`; religa e reprocessa. **Avise** que,
   logo após religar, o circuito pode ainda estar aberto (~20 s) e o comando espera nas filas de retry até fechar (o script espera até
   a turma voltar a `SINCRONIZADA`).
   *Decisão ilustrada: retry por fila sem bloquear o consumidor + DLQ + reprocesso.* O painel do RabbitMQ com 1 mensagem na `.dlq` é visual.
3. **Cenário 3 — o Moodle cria o curso, mas a resposta se perde (~30 s).** `.\scripts\cenarios\3-resposta-perdida.ps1`. Mostre
   `[FALHA-INJETADA] Moodle executou a criação criarCurso mas a resposta foi 'perdida'`, `[RETRY] ... retry-5s`, e, 5 s depois, o
   **mesmo comando** com `x-tentativa=2` terminando `SUCESSO` com **`cursosCriados=0`** (o curso já existia, foi achado por
   `idnumber`). *Decisão ilustrada: não repetir criação às cegas + retry por fila + idempotência no destino.*
4. **Cenário 4 — o serviço morre no meio (~1 min; se sobrar tempo).** `.\scripts\cenarios\4-reinicio-no-meio.ps1`. Mostre o
   `docker kill` no meio, a fila principal com 1 mensagem logo depois, o **mesmo `eventId`** reentregue quando o serviço volta e o
   `resumo` do 2º processamento com `cursosCriados=0` e `usuariosCriados=5` (só o que faltava). *Decisão ilustrada: ack só após
   concluir + reentrega + idempotência.*

Os cenários **5 (mensagem duplicada), 6 (RabbitMQ fora) e 7 (mensagem veneno)** são extras: use-os só se o professor perguntar
"e se a mensagem vier duplicada / o broker cair / chegar lixo?". O 5 é rápido (~30 s) e muito claro:
`[IDEMPOTENCIA] ... duplicata descartada, nada foi refeito` e `versao: 1` no estado.

### 12:30–14:00 — Fechamento: decisões e trade-offs

Resuma em quatro frases:

1. **Decomposição** por capacidade de negócio, três quanta (serviço + banco), sem acesso cruzado a dados. Troca: mais peças para operar, em troca de poder mudar/escalar/falhar cada uma separadamente.
2. **Comunicação assíncrona** por RabbitMQ, com contratos versionados e evento **completo**. Troca: consistência **eventual** e mais complexidade (idempotência, ordem, duplicatas).
3. **Resiliência** em camadas: timeout/retry/circuit breaker nas bordas, filas de retry + DLQ entre serviços, idempotência em todo consumidor.
4. **Observabilidade**: correlationId nos logs, métricas e tracing distribuído. Sem isso, falhas em sistemas distribuídos são invisíveis.

Termine citando **limitações conscientes** (seção abaixo) — mostra maturidade técnica.

---

## Perguntas prováveis e respostas curtas

**1. Por que mensageria, e não REST entre os serviços?**
Para evitar **acoplamento temporal**: com REST síncrono, se o sincronizador ou o Moodle estivessem fora, o monitor ficaria
travado ou perderia a mudança. Com fila, o produtor entrega ao broker e segue; o consumidor processa quando puder. Além disso,
o broker garante entrega e permite retry, DLQ e vários consumidores. REST só nas bordas, onde o outro lado já é uma API REST (iEducar, Moodle).

**2. Por que três serviços e não dois ou cinco?**
Cada um tem uma razão de mudança diferente (API do iEducar / regras de mapeamento e estado / API do Moodle) — ocultação de
informação e coesão. Menos que isso misturaria as duas pontas externas com a regra do meio; mais (por exemplo, um serviço por
entidade: turmas, alunos, matrículas) criaria muito acoplamento e problemas de ordem (a matrícula depende de curso e usuário).

**3. O que é *architecture quantum* e quantos o sistema tem?**
É a menor unidade implantável de forma independente, com alta coesão funcional e **tudo de que precisa para funcionar**
(incluindo os dados), e acoplada às outras de forma assíncrona. Aqui são **três**: cada serviço com o seu banco, ligados só por filas.
O T1 tinha um.

**4. Por que evento *completo* (TurmaAlterada com os dados) e não só o ID da turma?**
Com só o ID, o sincronizador teria de chamar o iEducar de volta para saber o que mudou — voltaria o acoplamento síncrono e o
sincronizador passaria a depender do iEducar. O evento completo deixa o consumidor **autônomo**. O custo: mensagens maiores e o
dado pode estar um pouco desatualizado quando consumido (aceitável aqui: a próxima varredura corrige).

**5. E se o RabbitMQ cair?**
Os serviços reconectam sozinhos. Quem publica espera o *publisher confirm*; sem confirmação, o monitor **não atualiza o snapshot**,
então a mudança é republicada na próxima varredura. Nada se perde (cenário 6). Mensagens já no broker sobrevivem porque as filas
são duráveis e as mensagens persistentes.

**6. E se a mesma mensagem chegar duas vezes?**
O RabbitMQ entrega "pelo menos uma vez", então isso acontece. Cada consumidor guarda o `eventId` processado numa tabela (PK) na
mesma transação do efeito; a duplicata é descartada (`[IDEMPOTENCIA]`). No Moodle, tudo é buscado por `idnumber` antes de criar
(cenários 3 e 6).

**7. Como vocês garantem consistência entre os bancos?**
Não há transação distribuída. A consistência é **eventual**: cada serviço é dono do seu dado e as mudanças se propagam por
mensagens idempotentes. O estado de cada turma (`ENVIADA`/`SINCRONIZADA`/`PARCIAL`/`FALHA`) é mantido pelo sincronizador, e o
resultado do publicador volta como evento. Versões por turma evitam que um resultado antigo sobrescreva um novo.

**8. O que é uma DLQ e quando a mensagem vai para lá?**
*Dead-letter queue*: fila de "mensagens que não deu certo". No publicador, depois de 3 tentativas (imediata → retry-5s → retry-30s);
no sincronizador, depois de 3 tentativas de uma falha possivelmente transitória, ou imediatamente se a mensagem é ilegível. Fica
guardada para análise e reprocesso (`POST /api/dlq/reprocessar`), sem bloquear o resto do fluxo.

**9. Qual a diferença entre retry e circuit breaker? Por que os dois?**
Retry tenta de novo uma chamada que falhou (falha passageira). Circuit breaker **para de tentar** quando as falhas se repetem,
para proteger o sistema externo e para não gastar tempo com chamadas condenadas. O retry resolve o "soluço"; o circuito resolve
a "queda". Juntos: tenta com espera crescente e, se persistir, abre o circuito e recusa rápido.

**10. Por que não usar API Gateway ou service mesh?**
Gateway resolve o problema de **clientes externos** falando com muitos serviços (tráfego norte-sul); aqui não há cliente externo,
só os serviços entre si por broker. Service mesh gerencia tráfego leste-oeste HTTP/gRPC entre muitos serviços e não cobre o tráfego
do broker; com 3 serviços seria custo sem benefício. As APIs HTTP que expomos (varredura, estado, DLQ) são administrativas.

**11. O que mudou em relação ao T1?**
T1: um programa que relia **tudo** a cada 2 min e reaplicava (idempotente, mas pesado), com chamadas diretas e um relatório por
execução. T2: detecção de mudança por **hash/snapshot** (só o que mudou circula), três serviços com dados próprios, mensagens
versionadas, resiliência em várias camadas (retry por fila, DLQ, circuit breaker) e observabilidade (correlationId, métricas, tracing).
A lógica de negócio com o Moodle (idnumber, nunca apaga, suspende inscrições) foi preservada.

**12. Por que o monitor faz *polling* e não recebe eventos do iEducar?**
O iEducar não publica eventos nem webhooks; só oferece API. Então o monitor "puxa" periodicamente (`MONITOR_INTERVALO`) e
transforma a diferença em evento. O custo é latência de até um intervalo. Para o monitor não reenviar tudo, compara o hash de cada turma com o último visto.

**13. Por que o comando `SincronizarTurma` é "grosso" (turma inteira) e não um comando por curso/usuário/inscrição?**
Simplicidade e menos pontos de falha: um comando aplica categoria → curso → usuários → inscrições na ordem certa. Com comandos
finos teríamos de coordenar dependências (uma **saga**) e lidar com ordem e compensações. O custo: falha parcial dentro da
turma — tratada com status `PARCIAL` e lista de erros, e como a operação é idempotente, reenviar completa o que faltou.

**14. Como vocês garantem a ordem das mensagens?**
Não dependemos de ordem global. Cada turma tem um **número de versão** no sincronizador; o publicador descarta comando com versão
menor do que a já aplicada, e o sincronizador ignora resultado obsoleto. Com um único consumidor por fila a ordem é natural; com
vários, a versão protege.

**15. O sincronizador publica no broker dentro de uma transação de banco. E se o commit falhar depois da publicação?**
O comando já saiu, mas o evento não foi marcado como processado, então ele é reentregue e o comando é republicado com o **mesmo
`commandId`** (derivado do `eventId`). O publicador descarta a duplicata. Não usamos *transactional outbox*; a combinação
confirm + id determinístico + idempotência cobre o caso.

**16. Como fazem logs e correlação?**
Padrão único com `corr=<correlationId>` no MDC, etiquetas fixas, e opção `LOG_FORMATO=json` (estruturado, com `correlationId` e
`traceId`). O correlationId vai no envelope e na propriedade `correlation_id` da mensagem. O script `acompanhar -Corr <id>`
filtra uma mudança de ponta a ponta.

**17. Como é o contrato das mensagens e como evoluir sem quebrar?**
Envelope com `eventId`, `tipo`, `versao`, `correlationId`, `dados`. Consumidores **tolerantes**: ignoram campos desconhecidos.
Adicionar campo opcional é compatível (continua v1). Mudança incompatível vira `.v2`, publicada ao lado da v1 até os consumidores migrarem.
Catálogo em `docs/contratos.md` e `docs/architecture.md`.

**18. Por que PostgreSQL por serviço, e não um banco compartilhado?**
Dados próprios são o que torna o serviço independente (quantum): schema evolui sem coordenar com os outros e ninguém depende de
tabela alheia. Um banco compartilhado seria um acoplamento escondido.

**19. Por que Java/Spring Boot?**
Reaproveitamos o T1 (cliente do iEducar/Moodle, regras e testes) e a equipe domina a stack; a escolha está justificada no ADR 0004. O kickoff permite stack livre.

**20. E se o Moodle criar o curso, mas a resposta se perder?**
É o "pior caso de rede": executou, mas não voltou. Por isso as chamadas de **criação não têm retry dentro da chamada** (só timeout e
circuit breaker): repetir `criarCurso` às cegas daria erro de duplicidade ou um segundo curso. A falha sobe como "Moodle
indisponível", o comando vai para `retry-5s` e, ao voltar, o fluxo **recomeça pela busca por `idnumber`**: acha o curso que já existe e
só faz o que falta (cenário 3: `cursosCriados=0` na 2ª tentativa). Leituras, atualizações e inscrições, idempotentes, mantêm retry rápido.

**21. E se o serviço cair no meio da sincronização?**
O consumidor só dá *ack* depois de terminar; se o processo morre, o broker devolve a mensagem à fila e ela é reentregue (mesmo
`eventId`). Como tudo é idempotente, o reprocesso reaproveita o que já foi criado e conclui (cenário 4, com `docker kill`). O mesmo vale
para o sincronizador (a transação não confirmou, o evento volta) e para o monitor (o snapshot não avançou, a mudança é republicada).

## Limitações conhecidas (diga antes que perguntem)

- **Consistência eventual**: entre uma mudança no iEducar e o Moodle há uma defasagem (até `MONITOR_INTERVALO` + processamento).
- **Sem *watchdog* para turma presa em `ENVIADA`**: se um resultado nunca chegar (por exemplo, mensagem perdida por falha grave do
  broker), a turma fica em `ENVIADA`. O sincronizador **não tem endpoint de reenvio**; a recuperação é por `POST
  /api/varreduras/completa` no monitor (republica tudo) ou, no caso de DLQ, `POST /api/dlq/reprocessar` no publicador.
- **Polling**, não eventos nativos do iEducar: latência de até um intervalo e carga periódica de leitura.
- **Nunca apaga no Moodle** (herdado do T1): turma inativa oculta o curso; matrícula cancelada suspende a inscrição. Renomear
  um curso do iEducar **não** renomeia a categoria já criada no Moodle.
- **Falhas injetadas só em memória**: `indisponivel`, `perderRespostaNaCriacao` e `latenciaMs` somem ao reiniciar o publicador (de propósito: o cenário 4 depende disso).
- **Segurança de demonstração**: RabbitMQ com `guest/guest`, sem TLS nem autenticação entre serviços, tokens de exemplo no
  `.env`. Em produção: credenciais por serviço, TLS, segredos fora do repositório.
- **Uma instância de cada serviço**: não testamos escala horizontal (vários consumidores da mesma fila).
- **Simuladores**: os cenários de falha foram ensaiados e validados com RabbitMQ/Postgres reais e os fakes do iEducar e do Moodle; a
  execução com os sistemas reais ainda precisa ser conferida na máquina — ensaie também no modo real antes da apresentação.
- **Sem testes de integração automatizados com broker**: os testes JUnit usam fakes em memória; o fluxo ponta a ponta é validado
  pelos scripts de cenários.

## Se algo der errado durante a apresentação

- **Um serviço não responde**: `docker compose ps` e `docker compose logs <serviço>`; `docker compose restart <serviço>` resolve a
  maioria. O estado está nos bancos e nas filas — reiniciar é seguro (é, aliás, o cenário 4).
- **Varredura diz `FALHA`**: leia o campo `erro` da resposta (`/api/varreduras/ultima`). Se é "circuito aberto", espere ~20 s.
- **Ficou confuso**: reinicie do zero com `docker compose down -v` e `.\scripts\subir-com-fakes.ps1` (cerca de 2 min).
- **Plano B**: tenha os trechos de log de [`failure-scenarios.md`](failure-scenarios.md) abertos e explique-os.
