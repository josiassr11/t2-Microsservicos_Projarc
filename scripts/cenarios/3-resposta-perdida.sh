#!/usr/bin/env bash
# CENÁRIO 3 (kickoff) - O Moodle CRIA o curso, mas a resposta NÃO chega ao publicador-moodle
# Prova: criar não é repetido às cegas; a fila de retry reexecuta o fluxo e a busca por idnumber encontra o que já existe.
source "$(dirname "${BASH_SOURCE[0]}")/../_comum.sh"
PUB="http://localhost:${PUBLICADOR_PORT}"; SINC="http://localhost:${SINCRONIZADOR_PORT}"

titulo "CENÁRIO 3: o Moodle cria o curso, mas a resposta se perde  (iEducar: $(modo_sistemas))"
echo "Pior caso de uma chamada de rede: o servidor EXECUTOU a operação e a resposta não voltou. Se o publicador repetisse"
echo "'criar curso', o Moodle recusaria (duplicado) ou criaria outro. Aqui injetamos exatamente isso: o curso é criado no"
echo "Moodle e, em seguida, o publicador 'perde' a resposta. (Requer FALHA_INJETAVEL=true, padrão do compose.)"
echo "Dica: em outro terminal rode  ./scripts/acompanhar.sh"

publicar_turma() { # id, nome, cursoId, cursoNome, alunoId, alunoNome
  local ev agora env; ev="$(nova_uuid)"; agora="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  env="{\"eventId\":\"${ev}\",\"tipo\":\"TurmaAlterada\",\"versao\":1,\"ocorridoEm\":\"${agora}\",\"correlationId\":\"${ev}\",\"origem\":\"script-cenario-3\",\"dados\":{\"mudanca\":\"CRIADA\",\"hash\":\"demo-${1}-${ev:0:8}\",\"turma\":{\"id\":${1},\"nome\":\"${2}\",\"ano\":2026,\"ativa\":true,\"escolaId\":1,\"escolaNome\":\"Escola Demo\",\"cursoId\":${3},\"cursoNome\":\"${4}\"},\"alunos\":[{\"id\":${5},\"nome\":\"${6}\",\"email\":null,\"ativo\":true}],\"professores\":[]}}"
  rabbit_publicar ieducar.eventos turma.alterada "$ev" "TurmaAlterada.v1" "$env"
}
aguardar_estado() { # turmaId, estado, segundos
  local i; for ((i=0; i<${3:-90}; i+=3)); do
    http_get "$SINC/api/estado/$1" | grep -q "\"estado\" *: *\"$2\"" && return 0; sleep 3; done; return 1
}

T=$((9100000 + 10#$(date +%H%M%S)))   # ids únicos por execução: dá para repetir o cenário e ver as criações de novo

passo "1. Preparando: a categoria 'Curso Demo Resposta Perdida' já existe no Moodle (turma 9100, sem falha)"
publicar_turma 9100 "Turma Base" 9100 "Curso Demo Resposta Perdida" 9100 "Aluno Base"
aguardar_estado 9100 SINCRONIZADA 60 && echo "   turma 9100 SINCRONIZADA" || aviso "turma 9100 não chegou a SINCRONIZADA"

passo "2. Armando a falha: a próxima CRIAÇÃO no Moodle executa, mas a resposta é 'perdida'"
http_post "$PUB/api/falhas/moodle" '{"perderRespostaNaCriacao":1}' | json

passo "3. Publicando a turma $T (mesmo curso): o curso Moodle será criado e a resposta se perde"
publicar_turma $T "Turma Resposta Perdida $T" 9100 "Curso Demo Resposta Perdida" $T "Aluno Resposta Perdida $T"
observe "publicador: [FALHA-INJETADA] Moodle executou a criação ... mas a resposta foi 'perdida' (simulado)"
observe "publicador: [RETRY] Moodle indisponível; reenfileirado em retry-5s, tentativa 2/3  (o comando NÃO foi repetido na hora)"
observe "publicador: 5s depois -> [RECEBE] de novo -> busca por idnumber ENCONTRA o curso -> continua e conclui (sem duplicar)"

passo "4. Aguardando a convergência (a turma deve terminar SINCRONIZADA, com 1 curso apenas)"
aguardar_estado $T SINCRONIZADA 90 && echo "   turma $T SINCRONIZADA" || aviso "turma $T não convergiu no prazo"
http_get "$SINC/api/estado/$T" | json
echo
echo "   Prova de que não duplicou: 'ultimoResumo.cursosCriados' = 0 (o curso já existia ao reexecutar) e há um único mapeamento do curso:"
http_get "$SINC/api/mapeamentos" | grep -o "IEDUCAR-TURMA-$T" | sort | uniq -c | sed 's/^/   mapeamentos com idnumber da turma (deve ser 1): /'
titulo "Fim do cenário 3"
