# Cenários de falha

O kickoff pede que a arquitetura responda a **quatro cenários de falha** e que o grupo mostre como reproduzi-los. Este
documento descreve cada um: o que acontece, como reproduzir (um script por cenário), o que observar nos logs (trechos **reais**,
capturados no ambiente em execução com iEducar/Moodle fakes), **por que** a arquitetura se comporta assim e qual conceito das
aulas está por trás.

## Mapa: kickoff × scripts

| # | Cenário do kickoff | Script (`scripts/cenarios/`, `.ps1` e `.sh`) | Mecanismo principal |
|---|---|---|---|
| 1 | O iEducar está fora do ar | `1-ieducar-fora` | timeout + retry + circuit breaker no monitor |
| 2 | O Moodle está fora do ar | `2-moodle-fora` | retry em filas (5 s / 30 s) → DLQ → reprocesso |
| 3 | O Moodle **cria** o curso, mas a resposta não chega | `3-resposta-perdida` | criação sem retry na chamada + retry por fila + busca por `idnumber` |
| 4 | Um serviço **reinicia** no meio da sincronização | `4-reinicio-no-meio` | *ack* só após concluir + reentrega + idempotência |
| 5 | (extra) Mensagem duplicada | `5-mensagem-duplicada` | consumidor idempotente |
| 6 | (extra) RabbitMQ fora do ar | `6-rabbitmq-fora` | publisher confirm; snapshot só avança após confirmação |
| 7 | (extra) Mensagem veneno | `7-mensagem-veneno` | rejeição sem requeue → DLQ |

Os cenários **1 a 4 são os do kickoff**; **5 a 7 são extras** que mostram outras garantias e entram só se sobrar tempo ou se o
professor perguntar.

## Antes de começar

Suba o ambiente (de preferência o **modo com fakes**, que é rápido e permite quebrar coisas à vontade):

```powershell
.\scripts\subir-com-fakes.ps1            # bash: ./scripts/subir-com-fakes.sh
```

Em **outro terminal**, deixe os logs abertos — é o que o espectador deve olhar:

```powershell
.\scripts\acompanhar.ps1                 # bash: ./scripts/acompanhar.sh
```

Os scripts dos cenários (`.\scripts\cenarios\N-nome.ps1` / `./scripts/cenarios/N-nome.sh`) detectam sozinhos se os sistemas são fakes ou reais. Cada um imprime o que está fazendo e o que
observar. Os logs seguem as etiquetas de [`docs/contratos.md`](contratos.md) §8: `[HTTP-OUT]`, `[RETRY]`, `[CIRCUITO]`,
`[PUBLICA]`, `[RECEBE]`, `[PROCESSA]`, `[IDEMPOTENCIA]`, `[DLQ]`, `[RESULTADO]`. O trecho `corr=<uuid>` é o correlationId: o
mesmo valor em todos os serviços identifica **uma** mudança.

Três ideias explicam quase tudo o que vem a seguir:

1. **Entrega "pelo menos uma vez".** O RabbitMQ prefere entregar duas vezes a perder uma. Por isso todo consumidor é idempotente.
2. **Confirmação antes de avançar.** Quem publica espera o *publisher confirm* do broker; quem consome só dá *ack* depois de
   terminar. Se algo cair no meio, a mensagem volta e é refeita.
3. **Idempotência no destino.** No Moodle, tudo é procurado por `idnumber` antes de ser criado; repetir uma operação não duplica nada.

---

## Cenário 1 — iEducar fora do ar

**Objetivo.** Mostrar que a queda do iEducar **não derruba** a integração: o monitor tenta de novo com espera crescente, abre o
circuito, para de insistir e se recupera sozinho quando o iEducar volta. Nada é enviado ao RabbitMQ com dados incompletos.

**Como reproduzir**

```powershell
.\scripts\cenarios\1-ieducar-fora.ps1        # bash: ./scripts/cenarios/1-ieducar-fora.sh
```

O script derruba o iEducar (fake: `/__admin/down`; real: `docker compose stop ieducar`), dispara varreduras, religa o iEducar e
continua disparando varreduras até uma terminar com sucesso.

**O que o espectador deve ver** (monitor-ieducar, trecho real):

```text
13:31:18.137 WARN  [monitor-ieducar] [corr=9fa2ca50-...] [HTTP-OUT] iEducar GET /api/school-class página=1 -> HTTP 503 em 11ms
13:31:18.142 WARN  [monitor-ieducar] [corr=9fa2ca50-...] [RETRY] iEducar tentativa 1/3 falhou (iEducar indisponível: HTTP 503 ...); nova tentativa em 500ms
13:31:18.653 WARN  [monitor-ieducar] [corr=9fa2ca50-...] [RETRY] iEducar tentativa 2/3 falhou (...); nova tentativa em 1000ms
13:31:19.677 WARN  [monitor-ieducar] [corr=9fa2ca50-...] [CIRCUITO] iEducar CLOSED -> OPEN
13:31:19.679 WARN  [monitor-ieducar] [corr=9fa2ca50-...] [RETRY] iEducar esgotou as 3 tentativas: iEducar indisponível: HTTP 503 ...
13:31:19.682 INFO  [monitor-ieducar] [corr=9fa2ca50-...] [VARREDURA] concluída id=4 status=FALHA lidas=0 publicadas=0 ... em 1556ms
13:31:21.711 WARN  [monitor-ieducar] [corr=3ab3e7fd-...] [CIRCUITO] iEducar ABERTO: chamada recusada sem tentar a rede
13:31:21.712 WARN  [monitor-ieducar] [corr=3ab3e7fd-...] [VARREDURA] ABORTADA, nada foi publicado: iEducar indisponível: circuito aberto (...) (nova tentativa na próxima varredura)
13:31:21.714 INFO  [monitor-ieducar] [corr=3ab3e7fd-...] [VARREDURA] concluída id=5 status=FALHA lidas=0 publicadas=0 inalteradas=0 falhas=0 em 5ms
```

Depois de ~20 s com o circuito aberto, ele passa a "meio-aberto", testa algumas chamadas e fecha. A resposta de
`POST /api/varreduras` passa de `"status":"FALHA"` com `"erro":"iEducar indisponível: circuito aberto ..."` para
`"status":"SUCESSO"`, e nos logs aparece `[CIRCUITO] ABERTO -> MEIO-ABERTO -> FECHADO`. As linhas `[PUBLICA]` **não** aparecem
durante a queda.

**Por que funciona**

- **Timeout** (3 s para conectar, 10 s para ler) impede que o monitor fique pendurado esperando um iEducar que não responde.
- **Retry com backoff exponencial** (500 ms, depois 1 s) cobre falhas passageiras sem martelar o sistema.
- **Circuit breaker**: depois de falhas repetidas, o circuito abre e as chamadas seguintes são recusadas em milissegundos
  (`em 5ms`) sem tocar na rede. Isso protege o iEducar (que está se recuperando) e o próprio monitor.
- **Leitura completa antes de publicar**: o monitor lê *tudo* do iEducar e só então compara e publica. Se a leitura falha, a
  varredura é abortada e **nada** vai para o broker — não existe evento com dados pela metade.
- **Sem estado a recuperar**: como o snapshot só avança quando há publicação confirmada, a próxima varredura bem-sucedida
  simplesmente enxerga as mudanças acumuladas.

---

## Cenário 2 — Moodle fora do ar (retry em filas → DLQ → reprocesso)

**Objetivo.** Uma mudança chega ao `publicador-moodle`, mas o Moodle está fora. O comando **não se perde**: espera nas filas de
retry (5 s e depois 30 s) e, esgotadas as tentativas, vai para a **dead-letter queue**, de onde pode ser reprocessado quando o
Moodle voltar. Enquanto isso, o resto do fluxo continua funcionando.

**Como reproduzir**

```powershell
.\scripts\cenarios\2-moodle-fora.ps1         # bash: ./scripts/cenarios/2-moodle-fora.sh
```

O script liga a falha simulada (`POST :8093/api/falhas/moodle {"indisponivel":true}`, só existe com `FALHA_INJETAVEL=true`),
muda uma turma no iEducar, dispara a varredura e acompanha as filas até o comando chegar à DLQ (~40–60 s). Depois desliga a
falha, chama `POST :8093/api/dlq/reprocessar` e espera a turma voltar a `SINCRONIZADA`. (Alternativa sem o endpoint: parar o
Moodle de verdade — fake: `docker stop fake-moodle`; real: `docker compose stop moodle`.)

**O que o espectador deve ver** (publicador-moodle, trecho real — mesma mudança, mesmo `corr`):

```text
13:34:34.387 [RECEBE]  fila=publicador-moodle.comandos tipo=SincronizarTurma.v1 turmaId=3 versão=3 x-tentativa=1
13:34:35.917 [RETRY]   Moodle indisponível; reenfileirado em retry-5s, tentativa 2/3 (turmaId=3, ...)
13:34:35.952 [PUBLICA] exchange=(default) rk=publicador-moodle.comandos.retry-5s ... x-tentativa=2 -> confirmado pelo broker em 30ms
13:34:40.954 [RECEBE]  ... x-tentativa=2                       <- voltou da fila retry-5s, 5 s depois
13:34:40.960 [CIRCUITO] Moodle: CLOSED -> OPEN
13:34:41.463 [RETRY]   Moodle indisponível; reenfileirado em retry-30s, tentativa 3/3 (...) causa: circuito aberto (buscarCurso não foi tentada)
13:35:11.470 [RECEBE]  ... x-tentativa=3                       <- voltou da fila retry-30s, 30 s depois
13:35:12.977 [DLQ]     turmaId=3 commandId=9f8b9149-...: Moodle indisponível após 3 tentativas; comando enviado à DLQ para reprocessamento manual.
13:35:12.982 [RESULTADO] turmaId=3 status=FALHA (tentativas=3)
13:35:13.003 [PUBLICA] exchange=moodle.eventos rk=turma.sincronizada TurmaSincronizada.v1 ... status=FALHA -> confirmado pelo broker em 3ms
```

No `sincronizador` (o dono do estado), o resultado de falha aparece como:

```text
13:35:13.019 [sincronizador] [RESULTADO] turma 3 v3: ENVIADA v3 -> FALHA (curso Moodle null, 0 mapeamentos, 1 erros, 3 tentativa(s))
```

Em `GET :8092/api/estado` a turma fica em `FALHA` com o campo `ultimoErro` explicando. Em `scripts/status` (ou no painel do
RabbitMQ) a fila `publicador-moodle.comandos.dlq` mostra 1 mensagem. Depois do reprocesso:

```text
13:35:22.832 [FALHA-INJETADA] Moodle marcado como disponível
13:35:22.845 [DLQ] reprocessamento solicitado: movendo mensagens da DLQ de volta para a fila principal
13:35:22.855 [DLQ] 1 mensagem(ns) devolvida(s) à fila publicador-moodle.comandos (x-tentativa=1)
13:35:22.859 [RETRY] Moodle indisponível; reenfileirado em retry-5s, tentativa 2/3 (...) causa: circuito aberto (buscarCurso não foi tentada)
13:35:27.869 [RETRY] ... reenfileirado em retry-30s, tentativa 3/3 (...) causa: circuito aberto ...
13:35:31.976 [CIRCUITO] Moodle: OPEN -> HALF_OPEN
13:35:57.977 [CIRCUITO] Moodle: HALF_OPEN -> CLOSED
13:35:58.032 [RESULTADO] turmaId=3 status=SUCESSO resumo=Resumo[cursosCriados=0, cursosAtualizados=1, ...] erros=0
```

```text
13:35:58.067 [sincronizador] [RESULTADO] turma 3 v3: FALHA v3 -> SINCRONIZADA (curso Moodle 2, 5 mapeamentos, 0 erros, 3 tentativa(s))
```

> **Observação importante (comportamento real):** logo depois de religar o Moodle, o **circuito pode estar ainda ABERTO**
> (ele fica aberto ~20 s a partir da última falha). Por isso o comando reprocessado não é aplicado na hora: ele cai de novo nas
> filas de retry (5 s e 30 s) e só é aplicado quando o circuito fecha — no log acima, cerca de 35 s depois do reprocesso. O
> script espera até 2 minutos pela conclusão. Não é falha: é o circuit breaker protegendo o Moodle de uma enxurrada logo após a
> volta. Se o espectador estranhar a demora, explique isso.

**Por que funciona**

- **Falha transitória ≠ falha de negócio.** Moodle fora do ar é transitório: o comando vai para as filas de retry. Já uma
  recusa do Moodle sobre um item (por exemplo, e-mail inválido) vira `PARCIAL` com a lista de `erros` e não é repetida.
- **Retry por fila, sem bloquear.** Em vez de dormir dentro do consumidor (o que travaria a fila principal), o comando é
  republicado em `retry-5s` / `retry-30s`. Essas filas têm TTL e, ao expirar, devolvem a mensagem à fila principal (dead-letter
  para o default exchange). O consumidor segue livre para outras mensagens.
- **DLQ = parada final com rastro.** Depois da 3ª tentativa a mensagem fica guardada na `.dlq` e o sincronizador é avisado
  (`status=FALHA`). Nada é descartado em silêncio.
- **Reprocesso seguro.** `POST /api/dlq/reprocessar` devolve as mensagens à fila principal com `x-tentativa=1`. Como a aplicação
  no Moodle é idempotente, reprocessar não duplica nada. O resultado `SUCESSO` que sucede o `FALHA` não é confundido com
  duplicata (o `eventId` de resultados `FALHA` é sempre novo — ver `docs/contratos.md` §3.3).

---

## Cenário 3 — O Moodle cria o curso, mas a resposta se perde

**Situação (o "pior caso de rede").** O publicador pede ao Moodle para criar um curso. O Moodle **executa** a operação, mas a
resposta HTTP não volta (timeout, queda da conexão). Para o publicador, a chamada "falhou" — mas o efeito **existe** no Moodle.
Se ele tentasse "criar de novo" na hora, o Moodle recusaria (já existe esse `idnumber`/`shortname`) ou, pior, criaria um
segundo curso.

**Decisão de projeto (ADR [0003](adr/0003-resiliencia.md)).** As chamadas de **criação** (`criarCategoria`, `criarCurso`,
`criarUsuario`) **não têm retry dentro da chamada**: só timeout e circuit breaker. Se falham de forma ambígua, a falha sobe como
"Moodle indisponível" e cai na política de **retry por fila** (`retry-5s` → `retry-30s` → DLQ). Quando o comando volta, o fluxo
**recomeça pela busca por `idnumber`**: o curso que o Moodle criou "às escuras" é encontrado e o processamento segue dali, sem
duplicar nada. Leituras, atualizações e inscrições, que são idempotentes por natureza, continuam com retry rápido na chamada.

**Como reproduzir**

```powershell
.\scripts\cenarios\3-resposta-perdida.ps1   # bash: ./scripts/cenarios/3-resposta-perdida.sh
```

O script (1) publica uma turma base para que a categoria do curso já exista; (2) arma a falha injetada
`POST :8093/api/falhas/moodle {"perderRespostaNaCriacao":1}` — a **próxima criação é executada de verdade no Moodle** e, em
seguida, o adaptador lança "Moodle indisponível" como se a resposta tivesse se perdido; (3) publica uma turma nova no mesmo curso
(os ids são únicos a cada execução, então o cenário pode ser repetido); (4) espera a convergência. A injeção só existe com
`FALHA_INJETAVEL=true` (padrão do compose) e vive só em memória.

**O que o espectador deve ver** (publicador-moodle, trecho real; turma 9205541):

```text
13:55:44.135 WARN  [FALHA-INJETADA] as próximas 1 criações no Moodle serão executadas, mas a resposta será 'perdida' (simulado)
13:55:44.168 INFO  [corr=f739269d-...] [RECEBE]   fila=publicador-moodle.comandos tipo=SincronizarTurma.v1 ... turmaId=9205541 versão=1 x-tentativa=1
13:55:44.173 INFO  [corr=f739269d-...] [PROCESSA] aplicando turmaId=9205541 versão=1 no Moodle (tentativa 1/3): 1 alunos, 0 professores, turma ativa
13:55:44.212 WARN  [corr=f739269d-...] [FALHA-INJETADA] Moodle executou a criação criarCurso mas a resposta foi 'perdida' (simulado); restam 0 perda(s)
13:55:44.213 WARN  [corr=f739269d-...] [RETRY] Moodle indisponível; reenfileirado em retry-5s, tentativa 2/3 (turmaId=9205541, commandId=fa2cc503-...) causa: ... resposta perdida após criarCurso (falha injetada)
13:55:44.218 INFO  [corr=f739269d-...] [PUBLICA] exchange=(default) rk=publicador-moodle.comandos.retry-5s ... x-tentativa=2 -> confirmado pelo broker em 2ms
13:55:49.222 INFO  [corr=f739269d-...] [RECEBE]   fila=publicador-moodle.comandos ... turmaId=9205541 versão=1 x-tentativa=2       <- 5 s depois, o MESMO comando
13:55:49.225 INFO  [corr=f739269d-...] [PROCESSA] aplicando turmaId=9205541 versão=1 no Moodle (tentativa 2/3): 1 alunos, 0 professores, turma ativa
13:55:49.264 INFO  [corr=f739269d-...] [RESULTADO] turmaId=9205541 status=SUCESSO resumo=Resumo[cursosCriados=0, cursosAtualizados=0, cursosOcultados=0, usuariosCriados=1, usuariosAtualizados=0, inscricoesCriadas=1, inscricoesSuspensas=0] erros=0
```

Observe o `resumo` da 2ª tentativa: **`cursosCriados=0`**. O curso já existia (criado na 1ª tentativa, cuja resposta se perdeu),
foi encontrado por `idnumber`, e só faltavam o usuário e a inscrição. O script ainda confirma que existe **um único**
mapeamento `IEDUCAR-TURMA-<id>`, e `GET :8092/api/estado/<id>` termina `SINCRONIZADA`.

**Por que funciona**

- **Não repetir às cegas.** Repetir uma operação de criação cuja primeira execução pode ter dado certo é o erro clássico; por isso
  a criação não tem retry interno.
- **Retry de mensagem recomeça do começo.** O comando é reentregue inteiro e o primeiro passo é sempre "buscar por `idnumber`".
  O fluxo converge para o mesmo estado final, não importa em que ponto a execução anterior parou.
- **Idempotência no destino.** `idnumber` (`IEDUCAR-CURSO-<id>`, `IEDUCAR-TURMA-<id>`, `IEDUCAR-ALUNO-<id>`,
  `IEDUCAR-SERVIDOR-<id>`) é a chave natural; o Moodle nunca é "criado duas vezes".
- Coberto também por teste automatizado: `ConvergenciaAposRespostaPerdidaTest` perde a resposta na criação da categoria, do
  curso e do usuário e reexecuta o comando; em todos os casos termina `SUCESSO` sem duplicar.

**Conceito da aula:** entrega garantida "pelo menos uma vez" + efeitos idempotentes; acoplamento temporal reduzido pelo
assíncrono.

---

## Cenário 4 — Um serviço reinicia no meio da sincronização

**Situação.** O `publicador-moodle` recebeu um comando, começou a aplicar no Moodle e o processo morreu (queda, deploy, `kill -9`)
antes de terminar.

**Como reproduzir**

```powershell
.\scripts\cenarios\4-reinicio-no-meio.ps1   # bash: ./scripts/cenarios/4-reinicio-no-meio.sh
```

O script (1) deixa o publicador **lento** (`POST :8093/api/falhas/moodle {"latenciaMs":1500}`: cada chamada ao Moodle espera
1,5 s, o que abre uma janela de vários segundos); (2) publica uma turma nova com 4 alunos e 1 professor; (3) espera 9 s e executa
`docker kill` no container do publicador (SIGKILL, sem desligamento limpo); (4) mostra as filas; (5) inicia o container de novo
e espera a convergência. A latência injetada some com o reinício (estado só em memória), então o 2º processamento é rápido.

**O que o espectador deve ver** (publicador-moodle, trecho real; turma 10254380):

```text
13:54:38.840 INFO  [corr=94ca1389-...] [RECEBE]   fila=publicador-moodle.comandos tipo=SincronizarTurma.v1 eventId=8183355e-... turmaId=10254380 versão=1 x-tentativa=1
13:54:38.843 INFO  [corr=94ca1389-...] [PROCESSA] aplicando turmaId=10254380 versão=1 no Moodle (tentativa 1/3): 4 alunos, 1 professores, turma ativa
13:54:43.370 INFO  [corr=94ca1389-...] [PROCESSA] categoria criada no Moodle (id=7, idnumber=IEDUCAR-CURSO-10254380)
13:54:44.880 INFO  [corr=94ca1389-...] [PROCESSA] turmaId=10254380 curso criado no Moodle (id=9, idnumber=IEDUCAR-TURMA-10254380)
                        ... (docker kill aqui: nenhuma linha [RESULTADO]; a fila principal volta a ter 1 mensagem) ...
13:54:54.827 INFO  [corr=-] Started PublicadorMoodleApplication in 5.983 seconds
13:54:54.941 INFO  [corr=94ca1389-...] [RECEBE]   fila=publicador-moodle.comandos ... eventId=8183355e-... turmaId=10254380 versão=1 x-tentativa=1   <- o MESMO comando (mesmo eventId)
13:54:54.959 INFO  [corr=94ca1389-...] [PROCESSA] aplicando turmaId=10254380 versão=1 no Moodle (tentativa 1/3): 4 alunos, 1 professores, turma ativa
13:54:55.224 INFO  [corr=94ca1389-...] [BANCO]    comando_processado gravado: commandId=8183355e-... turmaId=10254380 status=SUCESSO
13:54:55.224 INFO  [corr=94ca1389-...] [RESULTADO] turmaId=10254380 status=SUCESSO resumo=Resumo[cursosCriados=0, cursosAtualizados=0, cursosOcultados=0, usuariosCriados=5, usuariosAtualizados=0, inscricoesCriadas=5, inscricoesSuspensas=0] erros=0
```

No script, logo após o kill: `filas do RabbitMQ (mensagens): principal=1 retry-5s=0 retry-30s=0 dlq=0` — a mensagem **não confirmada
voltou para a fila**. O `resumo` do 2º processamento mostra **`cursosCriados=0`** e `usuariosCriados=5`: a categoria e o curso
já tinham sido criados antes do kill e não são recriados; só o que faltava foi feito.

**Por que funciona**

- **Ack só depois de concluir.** O consumidor confirma a mensagem ao final do processamento. Se o processo morre antes, o broker
  detecta a conexão perdida e a **reentrega** (mesmo `eventId`).
- **Idempotência.** O reprocesso começa pela busca por `idnumber`; o que já existia é reaproveitado.
- **`comando_processado` na mesma transação do resultado.** O comando só consta como processado se o resultado foi gravado; um
  kill no meio nunca deixa "meio registrado".
- **Mensagens persistentes e filas duráveis** sobrevivem à queda do serviço (e até do broker).
- O mesmo raciocínio vale para os outros serviços: se o **sincronizador** cai no meio de um evento, a transação não confirmou,
  então o evento volta e é refeito; se o **monitor** cai, o snapshot não avançou e a mudança é republicada na próxima varredura.

**Conceito da aula:** entrega garantida com *ack* tardio + consumidor idempotente; posse do estado (cada serviço no seu banco).

---

## Cenário 5 (extra) — Mensagem duplicada (idempotência)

**Objetivo.** Provar que processar a mesma mensagem duas vezes não cria cursos, usuários nem inscrições duplicados (consumidor idempotente, conceito da aula de mensageria).

**Como reproduzir**

```powershell
.\scripts\cenarios\5-mensagem-duplicada.ps1  # bash: ./scripts/cenarios/5-mensagem-duplicada.sh
```

O script publica, pela API HTTP de management do RabbitMQ, **o mesmo envelope** (mesmo `eventId`/`message_id`) duas vezes no
exchange `ieducar.eventos`, simulando uma reentrega. Usa uma turma de teste (id 9001), que cria o curso "Curso Demo
Duplicidade" no Moodle — é dado de demonstração.

**O que o espectador deve ver** (sincronizador, trecho real):

```text
13:36:15.737 [RECEBE]   fila=sincronizador.turma-alterada tipo=TurmaAlterada.v1 eventId=d755150a-... turmaId=9001 mudanca=CRIADA ...
13:36:15.740 [BANCO]    idempotência: evento d755150a-... gravado como processado (vale só se a transação confirmar)
13:36:15.744 [PROCESSA] turma 9001 (Turma Duplicidade) v1 -> ENVIADA (mudança CRIADA)
13:36:15.753 [PUBLICA]  exchange=sincronizacao.comandos rk=sincronizar.turma tipo=SincronizarTurma.v1 ... versao=1 -> confirmado pelo broker em 6 ms
13:36:15.883 [RESULTADO] turma 9001 v1: ENVIADA v1 -> SINCRONIZADA (curso Moodle 5, 3 mapeamentos, 0 erros, 1 tentativa(s))
13:36:19.828 [RECEBE]   fila=sincronizador.turma-alterada ... eventId=d755150a-... turmaId=9001 mudanca=CRIADA ...        <- a MESMA mensagem
13:36:19.830 [IDEMPOTENCIA] evento d755150a-... já processado (turma 9001): duplicata descartada, nada foi refeito
```

No fim, `GET :8092/api/estado/9001` mostra `"versao": 1` (e não 2) e apenas um curso no Moodle.

**Por que funciona**

- O sincronizador grava o `eventId` na tabela `evento_processado` (chave primária, `INSERT ... ON CONFLICT DO NOTHING`) **na
  mesma transação** que muda o estado da turma e publica o comando. Se a inserção não insere nada, é duplicata: ele dá `ack` e
  ignora.
- O comando enviado ao publicador tem um `commandId` **determinístico** (derivado do `eventId` do evento de origem), então
  mesmo se o evento fosse reprocessado, o publicador descartaria o comando repetido pela tabela `comando_processado` — e
  reemitiria o resultado já guardado.
- No fim da cadeia, o Moodle é acessado com "buscar por `idnumber` antes de criar": uma terceira linha de defesa.

---

## Cenário 6 (extra) — RabbitMQ fora do ar (publisher confirm)

**Objetivo.** O monitor só considera uma mudança "entregue" depois que o broker **confirma** o recebimento. Se o RabbitMQ está
fora, a publicação falha, o snapshot **não avança** e a mudança é republicada quando o broker volta. Nenhuma mudança se perde.

**Como reproduzir**

```powershell
.\scripts\cenarios\6-rabbitmq-fora.ps1       # bash: ./scripts/cenarios/6-rabbitmq-fora.sh
```

O script guarda o hash da turma, derruba o RabbitMQ (`docker compose stop rabbitmq`), muda uma turma no iEducar, dispara a
varredura, mostra que o snapshot **não mudou**, religa o RabbitMQ e dispara outra varredura.

**O que o espectador deve ver** (monitor-ieducar, trecho real):

```text
13:36:24.591 ERROR [monitor-ieducar] [corr=219e093c-...] [PUBLICA] FALHOU turmaId=3 eventId=1b2f882e-...: RabbitMQ indisponível: ... -> snapshot NÃO atualizado; será tentado de novo na próxima varredura
13:36:24.595 INFO  [monitor-ieducar] [corr=219e093c-...] [VARREDURA] concluída id=22 status=PARCIAL lidas=3 publicadas=0 inalteradas=2 falhas=1 em 31ms
   ... (RabbitMQ volta) ...
13:36:41.104 INFO  [monitor-ieducar] [corr=f164ab5d-...] [PUBLICA] exchange=ieducar.eventos rk=turma.alterada tipo=TurmaAlterada.v1 ... turmaId=3 mudanca=ATUALIZADA -> confirmado pelo broker em 27ms
13:36:41.136 INFO  [monitor-ieducar] [corr=f164ab5d-...] [VARREDURA] concluída id=23 status=SUCESSO lidas=3 publicadas=1 inalteradas=2 falhas=0 em 104ms
```

A resposta de `POST /api/varreduras` na queda traz `"status":"PARCIAL"`, `"falhasPublicacao":1`. O `GET :8091/api/snapshots` mostra o
mesmo `hash` de antes. Nos outros serviços aparece, a cada ~5 s, `WARN Consumer raised exception, processing can restart if the connection
factory supports it` (é a tentativa automática de reconexão ao broker); quando o RabbitMQ volta, eles reconectam sozinhos e seguem
processando, sem reiniciar nada.

**Por que funciona**

- O snapshot (o "já vi isto") só é atualizado **depois** do *publisher confirm*. Publicar sem confirmação = não aconteceu.
- Também é usado `mandatory=true`: mensagem sem fila ligada (*unroutable*) volta para o publicador e conta como falha — outra
  forma de não perder mudança.
- Como a mudança continua pendente (hash diferente do snapshot), **a próxima varredura a republica**. É um *outbox* simples
  baseado em comparação de estado.
- Se o monitor cair exatamente entre o confirm e a gravação do snapshot, a mudança é republicada com outro `eventId` — um evento
  logicamente duplicado, inofensivo porque o resto da cadeia é idempotente.

---

## Cenário 7 (extra) — Mensagem veneno (poison message)

**Objetivo.** Uma mensagem ilegível nunca será processada, por mais que tente. Ela não pode ficar voltando para a fila
(travaria o consumidor): é rejeitada **sem requeue** e vai para a DLQ, onde fica guardada para análise, enquanto as demais
mensagens continuam passando.

**Como reproduzir**

```powershell
.\scripts\cenarios\7-mensagem-veneno.ps1     # bash: ./scripts/cenarios/7-mensagem-veneno.sh
```

Publica o corpo `isto nao e json {{{` em `ieducar.eventos`.

**O que o espectador deve ver** (sincronizador, trecho real):

```text
13:36:49.218 ERROR [sincronizador] [corr=bcafd7ab-...] [DLQ] mensagem ilegível na fila sincronizador.turma-alterada (messageId=bcafd7ab-...): JSON ilegível: Unrecognized token 'isto': ...
```

(Segue um *stack trace* com `AmqpRejectAndDontRequeueException` — é esperado.) O script mostra a DLQ passando de 0 para 1; é possível
inspecionar a mensagem em http://localhost:15672 → *Queues* → `sincronizador.turma-alterada.dlq` → *Get messages*. Para limpar:
`curl -u guest:guest -X DELETE http://localhost:15672/api/queues/%2F/sincronizador.turma-alterada.dlq/contents`.

**Por que funciona.** O parse do envelope é feito pelo próprio serviço; se falha, ele lança `AmqpRejectAndDontRequeueException`.
O broker aplica a dead-letter (exchange `sincronizador.dlx`) e a mensagem vai para `sincronizador.turma-alterada.dlq`, sem retry
(retentar um JSON quebrado nunca resolve). Já falhas que *podem* ser transitórias (ex.: banco fora) têm 3 tentativas com espera
de 1 s antes da DLQ.

---

## Resumo: qual mecanismo cobre qual falha

| Falha | Mecanismo principal | Cenário |
|---|---|---|
| iEducar lento/fora | timeout + retry com backoff + circuit breaker; varredura abortada sem publicar | 1 |
| Moodle lento/fora | timeout + retry + circuit breaker; filas retry-5s/30s; DLQ; reprocesso | 2 |
| Resposta perdida após criar no Moodle | criação sem retry na chamada; retry por fila refaz desde a busca por `idnumber` | 3 |
| Serviço cai no meio | ack só após concluir; reentrega; idempotência | 4 |
| Mensagem entregue duas vezes | consumidor idempotente (`evento_processado`, `comando_processado`) | 5 |
| RabbitMQ fora | publisher confirm + `mandatory`; snapshot só avança após confirm; reconexão automática | 6 |
| Mensagem ilegível | rejeição sem requeue → DLQ do sincronizador | 7 |

## Limitações que valem ser ditas (honestidade na apresentação)

- **Sem watchdog para turma presa em `ENVIADA`.** Se um comando se perder de um jeito que nem o retry nem a DLQ cobrem, a turma
  fica em `ENVIADA` até chegar um novo evento (ou uma varredura completa). Não há temporizador que a detecte.
- **Sem endpoint de reenvio no sincronizador.** O reprocesso manual existe no publicador (`POST /api/dlq/reprocessar`) e no
  monitor (`POST /api/varreduras/completa`), mas o sincronizador não reemite comandos por conta própria.
- **Consistência eventual.** Entre a mudança no iEducar e o reflexo no Moodle há um intervalo (varredura + filas); o estado
  `ENVIADA → SINCRONIZADA | PARCIAL | FALHA` expõe isso.
- **Falhas injetadas vivem só em memória** (`indisponivel`, `perderRespostaNaCriacao`, `latenciaMs`) e somem ao reiniciar o container.
- **Validado com fakes.** Tudo foi exercitado com RabbitMQ/Postgres reais e iEducar/Moodle **fakes**; a execução completa com os
  sistemas reais ainda precisa ser conferida na máquina do usuário (no modo real, os cenários 2 e 6 renomeiam a primeira turma ativa do iEducar por SQL, por isso rode antes o `demo-seed base`;
  o 1 só para e religa o iEducar; os cenários 3, 4, 5 e 7 publicam eventos sintéticos direto no RabbitMQ e criam dados de demonstração no Moodle).
