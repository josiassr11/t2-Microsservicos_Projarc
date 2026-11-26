#!/usr/bin/env bash
# CENÁRIO 7 (extra) - Mensagem "veneno": corpo que não é JSON -> vai direto para a DLQ do sincronizador
source "$(dirname "${BASH_SOURCE[0]}")/../_comum.sh"

titulo "CENÁRIO 7: mensagem veneno (poison message)"
echo "Uma mensagem ilegível nunca vai ser processada, por mais que tente. Ela não pode ficar voltando para a fila"
echo "(travaria o consumidor): é rejeitada sem requeue e vai para a DLQ, onde fica guardada para análise."

ANTES="$(fila_qtd sincronizador.turma-alterada.dlq)"
passo "1. Publicando um corpo inválido em ieducar.eventos (DLQ do sincronizador antes: ${ANTES})"
rabbit_publicar ieducar.eventos turma.alterada "$(nova_uuid)" "TurmaAlterada.v1" "isto nao e json {{{"
sleep 5
observe "sincronizador: [RECEBE] fila=sincronizador.turma-alterada ... -> erro ao ler a mensagem -> [DLQ] rejeitada sem requeue"
echo "   DLQ do sincronizador agora: $(fila_qtd sincronizador.turma-alterada.dlq)"
echo "   Para inspecionar:  http://localhost:15672  (Queues > sincronizador.turma-alterada.dlq > Get messages)"
echo "   Para limpar:       curl -u guest:guest -X DELETE http://localhost:15672/api/queues/%2F/sincronizador.turma-alterada.dlq/contents"
titulo "Fim do cenário 7"
