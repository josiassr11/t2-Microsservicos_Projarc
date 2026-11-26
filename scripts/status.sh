#!/usr/bin/env bash
# Mostra o estado da integração: estado das turmas, mapeamentos, última varredura, DLQ e filas do RabbitMQ.
source "$(dirname "${BASH_SOURCE[0]}")/_comum.sh"

titulo "Última varredura do monitor-ieducar  (:${MONITOR_PORT})"
http_get "http://localhost:${MONITOR_PORT}/api/varreduras/ultima" | json
titulo "Estado das turmas no sincronizador  (:${SINCRONIZADOR_PORT})"
http_get "http://localhost:${SINCRONIZADOR_PORT}/api/estado" | json
titulo "Mapeamento iEducar <-> Moodle (dono: sincronizador)"
http_get "http://localhost:${SINCRONIZADOR_PORT}/api/mapeamentos" | json
titulo "Comandos processados pelo publicador-moodle  (:${PUBLICADOR_PORT})"
http_get "http://localhost:${PUBLICADOR_PORT}/api/comandos" | json
titulo "DLQ do publicador"
http_get "http://localhost:${PUBLICADOR_PORT}/api/dlq" | json
titulo "Filas do RabbitMQ"
if command -v jq >/dev/null 2>&1; then
  curl -sS -m 10 -u "$RABBIT_AUTH" "$RABBIT_API/queues" | jq -r '(["FILA","PRONTAS","SEM_ACK","CONSUMIDORES"] | @tsv), (.[] | [.name, (.messages_ready // 0), (.messages_unacknowledged // 0), (.consumers // 0)] | @tsv)' | column -t -s $'\t'
else
  curl -sS -m 10 -u "$RABBIT_AUTH" "$RABBIT_API/queues" | grep -o '"name":"[^"]*"' ; mostra_filas
fi
