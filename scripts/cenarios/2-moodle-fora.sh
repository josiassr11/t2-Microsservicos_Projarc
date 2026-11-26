#!/usr/bin/env bash
# CENÁRIO 2 - Moodle fora do ar (publicador-moodle: retry em filas 5s/30s -> DLQ -> reprocessamento)
source "$(dirname "${BASH_SOURCE[0]}")/../_comum.sh"
MON="http://localhost:${MONITOR_PORT}"; PUB="http://localhost:${PUBLICADOR_PORT}"; SINC="http://localhost:${SINCRONIZADOR_PORT}"

titulo "CENÁRIO 2: Moodle fora do ar  (iEducar: $(modo_sistemas))"
echo "Uma mudança no iEducar chega ao publicador-moodle, mas o Moodle está fora. O comando NÃO se perde:"
echo "vai para a fila retry-5s, depois retry-30s e, esgotadas as tentativas, para a DLQ. Quando o Moodle volta,"
echo "reprocessamos a DLQ e a turma é sincronizada. (Requer FALHA_INJETAVEL=true, que é o padrão do compose.)"
echo "Dica: em outro terminal rode  ./scripts/acompanhar.sh"

passo "1. Simulando o Moodle indisponível no publicador-moodle"
http_post "$PUB/api/falhas/moodle" '{"indisponivel":true}' | json
echo "   (alternativa sem o endpoint: docker compose stop moodle  /  docker start fake-moodle)"

passo "2. Mudando algo no iEducar e disparando a varredura"
ieducar_renomear_turma "Turma Cenario Moodle $(date +%H%M%S)"
http_post "$MON/api/varreduras" | json
observe "monitor:     [VARREDURA] 1 turma alterada -> [PUBLICA] TurmaAlterada.v1 (confirmado pelo broker)"
observe "sincronizador: [RECEBE] TurmaAlterada.v1 -> [PUBLICA] SincronizarTurma.v1 (comando)"
observe "publicador:  [RECEBE] SincronizarTurma.v1 -> [HTTP-OUT] Moodle ... falhou -> [RETRY] -> [CIRCUITO]"
observe "publicador:  [RETRY] tentativa 1 -> fila retry-5s ...; tentativa 2 -> fila retry-30s ...; tentativa 3 -> [DLQ]"
observe "publicador:  [PUBLICA] TurmaSincronizada.v1 status=FALHA  |  sincronizador: [RESULTADO] turma em estado FALHA"

passo "3. Acompanhando as filas até o comando chegar à DLQ (leva ~40-60s)"
for i in $(seq 1 12); do
  mostra_filas
  [ "$(fila_qtd publicador-moodle.comandos.dlq)" != "0" ] && [ "$(fila_qtd publicador-moodle.comandos.dlq)" != "n/a" ] && break
  sleep 8
done
echo "   estado da turma no sincronizador:"; http_get "$SINC/api/estado" | json

passo "4. Moodle voltou: desligando a falha simulada"
http_post "$PUB/api/falhas/moodle" '{"indisponivel":false}' | json

passo "5. Reprocessando a DLQ"
http_post "$PUB/api/dlq/reprocessar" | json
observe "publicador: [DLQ] reprocessando N mensagem(ns) -> [RECEBE] SincronizarTurma.v1 -> [HTTP-OUT] Moodle 200 -> [PUBLICA] TurmaSincronizada.v1 status=SUCESSO"
observe "Se o circuito ainda estiver ABERTO (~20s após a última falha), o comando espera nas filas retry-5s/30s e é aplicado assim que o circuito fecha."
echo "   aguardando a turma voltar a SINCRONIZADA (até ~2 min)..."
for i in $(seq 1 24); do
  est="$(http_get "$SINC/api/estado")"
  echo "$est" | grep -Eq '"FALHA" *: *0' && echo "$est" | grep -Eq '"ENVIADA" *: *0' && break
  sleep 5
done
mostra_filas
echo "   estado final no sincronizador:"; http_get "$SINC/api/estado" | json
titulo "Fim do cenário 2"
