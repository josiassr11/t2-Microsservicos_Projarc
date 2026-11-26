#!/usr/bin/env bash
# CENÁRIO 6 (extra) - RabbitMQ fora do ar (monitor: publisher confirm; snapshot só avança depois da confirmação)
source "$(dirname "${BASH_SOURCE[0]}")/../_comum.sh"
MON="http://localhost:${MONITOR_PORT}"

titulo "CENÁRIO 6: RabbitMQ fora do ar  (iEducar: $(modo_sistemas))"
echo "O monitor só considera uma mudança 'entregue' depois que o broker CONFIRMA o recebimento. Se o RabbitMQ"
echo "está fora, a publicação falha, o snapshot NÃO avança e a mudança é republicada quando o broker volta."
echo "Dica: em outro terminal rode  ./scripts/acompanhar.sh"

passo "1. Snapshots atuais do monitor (guarde o hash da turma)"
http_get "$MON/api/snapshots" | json

passo "2. Derrubando o RabbitMQ"
compose stop rabbitmq
observe "sincronizador/publicador: erros de conexão com o broker e tentativas automáticas de reconexão"

passo "3. Mudando algo no iEducar e disparando a varredura"
ieducar_renomear_turma "Turma Cenario Rabbit $(date +%H%M%S)"
http_post "$MON/api/varreduras" "" 90 | json
observe "monitor: [VARREDURA] 1 turma alterada -> [PUBLICA] FALHA ao publicar (sem confirmação do broker)"
observe "monitor: snapshot NÃO atualizado (a mudança continua pendente)"

passo "4. Snapshots depois da falha: o hash da turma deve ser o MESMO de antes"
http_get "$MON/api/snapshots" | json

passo "5. Religando o RabbitMQ"
compose start rabbitmq
esperar_saudavel rabbitmq 120 && echo "   RabbitMQ saudável" || aviso "RabbitMQ demorou"
pausa 10
observe "sincronizador/publicador: [RECEBE] voltam a consumir (reconexão automática; filas duráveis foram preservadas)"

passo "6. Nova varredura: a mudança pendente é republicada e agora confirmada"
http_post "$MON/api/varreduras" "" 90 | json
observe "monitor: [PUBLICA] TurmaAlterada.v1 confirmado pelo broker -> snapshot atualizado"
observe "sincronizador e publicador: o fluxo completo roda e a turma chega ao Moodle"
pausa 8
http_get "$MON/api/snapshots" | json
titulo "Fim do cenário 6"
