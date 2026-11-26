# ADR 0007 — Comando de granularidade grossa (`SincronizarTurma`) em vez de saga

- **Status:** aceita
- **Data:** 2026-11-26

## Contexto

Aplicar uma turma no Moodle exige vários passos **dependentes** entre si: categoria do curso → curso da turma → usuários
(alunos e professores) → inscrições (papel aluno/professor, ativa/suspensa). Poderíamos modelar isso como um **fluxo
distribuído** (uma mensagem por passo, cada passo um serviço ou consumidor, com **saga** e compensações) ou como **um
único comando** que o publicador executa por inteiro.

## Decisão

Um **comando coarse-grained por turma**: `SincronizarTurma.v1` leva a turma, os alunos e os professores completos. O
`publicador-moodle` executa **todos os passos** em um único processamento (sem transação distribuída) e devolve **um** resultado
(`TurmaSincronizada.v1`) com `status` `SUCESSO`, `PARCIAL` ou `FALHA`, o `resumo` do que foi feito, os `mapeamentos` e
os `erros` por item.

Por que isso é seguro sem saga:

- **Todos os passos são idempotentes por `idnumber`**: o publicador sempre busca antes de criar (`IEDUCAR-CURSO-<id>`,
  `IEDUCAR-TURMA-<id>`, `IEDUCAR-ALUNO-<id>`, `IEDUCAR-SERVIDOR-<id>`; usernames `aluno<id>`/`prof<id>`). Repetir o
  comando inteiro (por reentrega, retry por fila ou reprocesso da DLQ) **converge** para o mesmo estado sem duplicar.
- **Nunca apagamos** nada no Moodle: turma inativa → curso oculto; matrícula cancelada ou vínculo removido → inscrição
  **suspensa**. Logo **não há o que "desfazer"**: não existem efeitos que exijam compensação.
- **Falha parcial é um resultado válido, não um erro de protocolo.** Se um aluno falha (ex.: e-mail recusado), os demais
  são aplicados e o resultado é `PARCIAL` com a lista de `erros`; o estado fica visível em `/api/estado`.
- O comando carrega o **estado desejado completo** da turma (não deltas), então um comando mais novo substitui o anterior
  e comandos obsoletos (versão menor) podem ser descartados com segurança.

## Alternativas consideradas

1. **Saga orquestrada** (um orquestrador envia `CriarCategoria`, `CriarCurso`, `CriarUsuario`, `Inscrever`... e espera
   cada resposta; em caso de falha dispara compensações). *Rejeitada:* multiplicaria mensagens, filas e estados
   (explosão de DLQs e de cenários de falha), para compensar efeitos que **nunca apagamos** e que já são idempotentes.
   A saga serve quando há efeitos **não idempotentes e reversíveis** em serviços **distintos** com transações locais;
   aqui o "serviço" externo é um só (o Moodle) e as operações convergem sozinhas.
2. **Saga coreografada** (cada passo reage ao evento do anterior). *Rejeitada:* é mais difícil de entender e depurar,
   e o fluxo "ao vivo" ficaria espalhado pelos serviços.
3. **Granularidade fina por entidade** (uma mensagem por aluno/inscrição). *Rejeitada* pelos mesmos motivos do ADR 0001
   (acoplamento e ordenação) e por gerar tempestade de mensagens numa turma grande.

## Consequências / trade-offs

- (+) Fluxo curto, fácil de explicar, observar (`correlationId` por turma) e reprocessar (uma mensagem = uma turma).
- (+) A idempotência fica concentrada em um lugar (busca por `idnumber` + inbox por `command_id`).
- (−) **Comandos grandes** em turmas muito grandes e **menos paralelismo** (a turma é aplicada em série).
- (−) Falha de **um** passo transitório repete o comando inteiro (as buscas por `idnumber` fazem a repetição ser barata,
  mas não é gratuita). É também o que torna seguro o cenário "o Moodle criou, mas a resposta se perdeu": o retry de
  mensagem refaz o comando inteiro começando pela busca (ADR 0003, `3-resposta-perdida`).
- (−) Não há "tudo ou nada": o resultado `PARCIAL` é **parte do contrato** e precisa ser tratado por quem consome.

## Quando passaria a valer a pena uma saga

- Se a integração passasse a incluir **efeitos não idempotentes ou irreversíveis** (ex.: cobrança, envio de e-mail
  único, criação de recurso externo sem chave natural).
- Se o fluxo atravessasse **vários serviços com bancos próprios** que precisassem ficar mutuamente coerentes
  (ex.: matrícula no Moodle **e** liberação de acesso em outro sistema, com desfazer se um deles falhar).
- Se houvesse a regra "ou aplica tudo ou nada" para o Moodle (compensar exigiria apagar/ocultar o que foi feito).
