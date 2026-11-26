#!/usr/bin/env bash
# CENÁRIO 4 (kickoff) - Um serviço REINICIA no meio da sincronização
# Prova: a mensagem só é confirmada (ack) ao terminar; se o serviço morre, o broker a reentrega e o reprocesso é idempotente.
source "$(dirname "${BASH_SOURCE[0]}")/../_comum.sh"
PUB="http://localhost:${PUBLICADOR_PORT}"; SINC="http://localhost:${SINCRONIZADOR_PORT}"

titulo "CENÁRIO 4: o publicador-moodle morre no meio da sincronização  (iEducar: $(modo_sistemas))"
echo "Deixamos o publicador lento (cada chamada ao Moodle espera 1,5 s) para abrir uma janela, publicamos uma turma com"
echo "vários alunos e matamos o container (kill -9) no meio. O comando ainda não tinha sido confirmado (ack) ao broker,"
echo "então volta para a fila; ao reiniciar, o serviço o reprocessa e, como tudo é idempotente, termina sem duplicar."
echo "Dica: em outro terminal rode  ./scripts/acompanhar.sh"

aguardar_estado() { local i; for ((i=0; i<${3:-90}; i+=3)); do
    http_get "$SINC/api/estado/$1" | grep -q "\"estado\" *: *\"$2\"" && return 0; sleep 3; done; return 1; }

passo "1. Deixando o publicador lento (latenciaMs=1500)"
http_post "$PUB/api/falhas/moodle" '{"latenciaMs":1500}' | json

T=$((9200000 + 10#$(date +%H%M%S) * 10))   # ids únicos por execução
passo "2. Publicando a turma $T (1 curso, 4 alunos, 1 professor)"
EV="$(nova_uuid)"; AGORA="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ENV="{\"eventId\":\"${EV}\",\"tipo\":\"TurmaAlterada\",\"versao\":1,\"ocorridoEm\":\"${AGORA}\",\"correlationId\":\"${EV}\",\"origem\":\"script-cenario-4\",\"dados\":{\"mudanca\":\"CRIADA\",\"hash\":\"demo-${T}-${EV:0:8}\",\"turma\":{\"id\":${T},\"nome\":\"Turma Reinicio ${T}\",\"ano\":2026,\"ativa\":true,\"escolaId\":1,\"escolaNome\":\"Escola Demo\",\"cursoId\":${T},\"cursoNome\":\"Curso Demo Reinicio\"},\"alunos\":[{\"id\":$((T+1)),\"nome\":\"Aluno Um\",\"email\":null,\"ativo\":true},{\"id\":$((T+2)),\"nome\":\"Aluno Dois\",\"email\":null,\"ativo\":true},{\"id\":$((T+3)),\"nome\":\"Aluno Tres\",\"email\":null,\"ativo\":true},{\"id\":$((T+4)),\"nome\":\"Aluno Quatro\",\"email\":null,\"ativo\":true}],\"professores\":[{\"id\":$((T+9)),\"nome\":\"Professor Demo\",\"email\":null,\"ativo\":true}]}}"
rabbit_publicar ieducar.eventos turma.alterada "$EV" "TurmaAlterada.v1" "$ENV"
echo "   aguardando 9s: o publicador já está no meio do trabalho (criando categoria, curso, usuários...)"; sleep 9
observe "publicador: [RECEBE] SincronizarTurma.v1 ... [HTTP-OUT] Moodle ... (lento) — e ainda SEM [RESULTADO]"

passo "3. MATANDO o publicador-moodle (docker kill)"
docker kill "${PROJETO}-publicador-moodle-1" >/dev/null && echo "   container publicador-moodle morto (SIGKILL)"
mostra_filas
observe "RabbitMQ: a mensagem não confirmada (unacked) volta para a fila principal quando a conexão do serviço cai"

passo "4. Reiniciando o publicador-moodle"
docker start "${PROJETO}-publicador-moodle-1" >/dev/null
esperar_saudavel publicador-moodle 120 && echo "   publicador de volta (a latência injetada some: o estado de falhas é só em memória)"
observe "publicador: [RECEBE] SincronizarTurma.v1 (mesma mensagem, reentregue) -> busca por idnumber -> só cria o que faltava -> [RESULTADO] SUCESSO"

passo "5. Aguardando a convergência"
aguardar_estado $T SINCRONIZADA 120 && echo "   turma $T SINCRONIZADA" || aviso "turma $T não convergiu no prazo"
http_get "$SINC/api/estado/$T" | json
echo
echo "   Veja 'ultimoResumo': só aparecem as criações que o 2º processamento precisou fazer (o resto já existia do 1º)."
sleep 6   # o painel de gerenciamento do RabbitMQ atualiza as contagens a cada ~5s
mostra_filas
titulo "Fim do cenário 4"
