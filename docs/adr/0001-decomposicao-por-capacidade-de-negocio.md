# ADR 0001 — Decomposição por capacidade de negócio (três serviços), não por entidade

- **Status:** aceita
- **Data:** 2026-11-26
- **Contexto da disciplina:** aula *Introdução aos Microsserviços* e *Decomposição de Microsserviços*; kickoff do T2 (slide 9).

## Contexto

No T1 a integração iEducar → Moodle era um monólito: uma única aplicação lia o iEducar, decidia o que mudou, mantinha o
vínculo entre os identificadores dos dois sistemas e escrevia no Moodle. Para o T2 o kickoff exige **pelo menos três
serviços, cada um com responsabilidade clara e coesa, donos dos próprios dados**, e que o critério de decomposição seja
justificado.

Precisamos escolher *por onde cortar* o monólito. As aulas apresentam critérios de fronteira (ocultação de informação,
coesão, acoplamento, Lei de Constantine) e estratégias de decomposição (por capacidade de negócio / modelagem pelo
domínio, por entidade/subdomínio técnico, por camada).

## Decisão

Decompor **por capacidade de negócio** — cada serviço realiza uma etapa do fluxo de integração, de ponta a ponta
(regra + dados + integração externa dela):

| Serviço | Capacidade de negócio | Segredo que esconde (*information hiding*) | Dados próprios |
|---|---|---|---|
| `monitor-ieducar` | **Detectar mudanças** no iEducar | Como se lê o iEducar (API REST + API legada, paginação, formatos) e como se decide que "mudou" (hash canônico por turma) | Snapshot (hash) por turma, histórico de varreduras |
| `sincronizador` | **Manter a integração**: mapeamento iEducar↔Moodle e estado de cada turma | Como os identificadores dos dois mundos se relacionam e em que estado cada turma está | Estado por turma, mapeamentos, eventos processados |
| `publicador-moodle` | **Aplicar no Moodle** | Como se fala com os Web Services do Moodle (funções `core_*`/`enrol_*`, `idnumber`, papéis, erros em HTTP 200) | Inbox de comandos já executados |

Fronteiras justificadas pelos critérios das aulas:

- **Ocultação de informação.** Só o monitor conhece o formato do iEducar; só o publicador conhece o formato do Moodle. As
  mensagens usam o vocabulário do domínio (turma, aluno, professor) e não o JSON de nenhum dos sistemas.
- **Coesão** ("o que muda junto fica junto"). Mudou a API do iEducar → mexe só no monitor. Mudou o Moodle → só no
  publicador. Mudou a regra de mapeamento/estado → só no sincronizador.
- **Acoplamento baixo.** Os serviços não se chamam diretamente; conversam por mensagens versionadas (ADR 0002).
- **Lei de Constantine** (estrutura estável = coesão alta + acoplamento baixo): o corte por capacidade maximiza a coesão
  interna e deixa só o contrato de mensagens entre os serviços.
- **Architecture quantum.** Cada serviço tem banco próprio e é implantável de forma independente → três quanta. O monólito
  do T1 (um banco/um processo) era um único quantum.
- **Posse do estado.** Cada dado tem um único dono; nenhum serviço lê o banco do outro (ADR 0005).
- **Modelagem pelo domínio / Lei de Conway.** O corte espelha o fluxo do problema (origem → integração → destino), que
  também corresponde a três "responsabilidades de equipe" naturais: quem conhece o iEducar, quem conhece a regra da
  integração e quem conhece o Moodle.

## Alternativas consideradas

1. **Por entidade** (serviço de Turmas, serviço de Alunos, serviço de Professores, serviço de Matrículas). *Rejeitada.*
   Uma inscrição no Moodle depende de o curso **e** o usuário já existirem; separar por entidade criaria conversa
   excessiva entre serviços e problemas de ordenação (qual chega primeiro?) — é baixa coesão e alto acoplamento, o oposto
   do que a Lei de Constantine recomenda. Cada entidade também espalharia o conhecimento do *mesmo* sistema externo
   (iEducar/Moodle) por vários serviços, violando a ocultação de informação.
2. **Por camada técnica** (um serviço de "leitura", um de "banco", um de "API"). *Rejeitada:* toda mudança de negócio
   atravessaria todos os serviços (baixa coesão funcional) e nenhum seria um quantum independente.
3. **Manter o monólito** (T1). *Fora do escopo:* o objetivo do T2 é justamente reprojetar a integração com mensagens.
4. **Mais de três serviços** (separar, por exemplo, "mapeamento" de "estado"). *Rejeitada por ora:* ambos mudam juntos e
   compartilham o mesmo dono de dados; separar só aumentaria a conversa (coesão mais baixa).

## Consequências / trade-offs

- (+) Cada serviço é pequeno, testável isoladamente e pode falhar/escalar sem derrubar os demais (ver ADR 0003).
- (+) Trocar o Moodle por outro LMS tocaria apenas o `publicador-moodle`.
- (−) Há custo operacional maior que no monólito: três processos, três bancos, um broker, consistência **eventual**
  (uma turma passa por `ENVIADA` antes de `SINCRONIZADA`; ADR 0005) e diagnóstico distribuído (compensado por
  `correlationId`, métricas e tracing — `docs/architecture.md`).
- (−) O sincronizador é um ponto de coordenação lógica; ele é tolerante a falhas porque é só consumidor/produtor de
  mensagens com estado persistido, mas não é "mágico": se ele ficar parado, as mensagens esperam na fila.
