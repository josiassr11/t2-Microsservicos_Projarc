#!/usr/bin/env bash
# CENÁRIO 5 (extra) - Mensagem duplicada (consumidor idempotente no sincronizador)
source "$(dirname "${BASH_SOURCE[0]}")/../_comum.sh"
SINC="http://localhost:${SINCRONIZADOR_PORT}"

titulo "CENÁRIO 5: mensagem duplicada"
echo "O RabbitMQ garante entrega 'pelo menos uma vez': a mesma mensagem pode chegar duas vezes. Publicamos o MESMO"
echo "envelope (mesmo eventId/message_id) duas vezes direto no exchange ieducar.eventos, pela API HTTP do RabbitMQ."
echo "Dica: em outro terminal rode  ./scripts/acompanhar.sh"

EVENTO="$(nova_uuid)"; AGORA="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ENVELOPE="{\"eventId\":\"${EVENTO}\",\"tipo\":\"TurmaAlterada\",\"versao\":1,\"ocorridoEm\":\"${AGORA}\",\"correlationId\":\"${EVENTO}\",\"origem\":\"script-cenario-5\",\"dados\":{\"mudanca\":\"CRIADA\",\"hash\":\"demo-duplicidade\",\"turma\":{\"id\":9001,\"nome\":\"Turma Duplicidade\",\"ano\":2026,\"ativa\":true,\"escolaId\":1,\"escolaNome\":\"Escola Demo\",\"cursoId\":9001,\"cursoNome\":\"Curso Demo Duplicidade\"},\"alunos\":[{\"id\":9001,\"nome\":\"Aluno Duplicidade\",\"email\":null,\"ativo\":true}],\"professores\":[]}}"

passo "1. Publicando o evento ${EVENTO} (1a vez)"
rabbit_publicar ieducar.eventos turma.alterada "$EVENTO" "TurmaAlterada.v1" "$ENVELOPE"
sleep 4
passo "2. Publicando o MESMO evento de novo (2a vez)"
rabbit_publicar ieducar.eventos turma.alterada "$EVENTO" "TurmaAlterada.v1" "$ENVELOPE"
sleep 4
observe "sincronizador: 1a vez -> [RECEBE] ... [PROCESSA] ... [PUBLICA] SincronizarTurma.v1"
observe "sincronizador: 2a vez -> [RECEBE] ... [IDEMPOTENCIA] evento ${EVENTO} já processado: descartado (sem novo comando)"

passo "3. Estado da turma 9001 no sincronizador: 'versao' deve ser 1 (e não 2)"
http_get "$SINC/api/estado/9001" | json
echo
echo "   (a turma de teste 9001 cria um curso 'Curso Demo Duplicidade' no Moodle; é só dado de demonstração)"
titulo "Fim do cenário 5"
