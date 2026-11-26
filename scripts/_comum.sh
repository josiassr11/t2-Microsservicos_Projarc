#!/usr/bin/env bash
# Funções comuns dos scripts de demonstração do T2 (use com:  source "$(dirname "$0")/_comum.sh").
# Requer: docker, curl. Opcional: jq (saída JSON formatada).

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJETO="t2-microsservicos"
REDE="${PROJETO}_default"

# lê VAR de .env (se existir) ou usa o padrão
env_get() {
  local nome="$1" padrao="$2" valor=""
  if [ -f "$RAIZ/.env" ]; then
    valor="$(grep -E "^${nome}=" "$RAIZ/.env" | tail -1 | cut -d= -f2- | tr -d '\r')"
  fi
  echo "${valor:-$padrao}"
}

IEDUCAR_PORT="$(env_get IEDUCAR_PORT 8080)"
MOODLE_PORT="$(env_get MOODLE_PORT 8081)"
MONITOR_PORT="$(env_get MONITOR_PORT 8091)"
SINCRONIZADOR_PORT="$(env_get SINCRONIZADOR_PORT 8092)"
PUBLICADOR_PORT="$(env_get PUBLICADOR_PORT 8093)"
RABBIT_API="http://localhost:15672/api"
RABBIT_AUTH="$(env_get RABBITMQ_USER guest):$(env_get RABBITMQ_PASSWORD guest)"

# cores (só em terminal)
if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_CIANO=$'\033[36m'; C_AMARELO=$'\033[33m'; C_VERDE=$'\033[32m'; C_VERMELHO=$'\033[31m'; C_NEGRITO=$'\033[1m'
else
  C_RESET=""; C_CIANO=""; C_AMARELO=""; C_VERDE=""; C_VERMELHO=""; C_NEGRITO=""
fi

titulo()  { echo; echo "${C_NEGRITO}${C_CIANO}==== $* ====${C_RESET}"; }
passo()   { echo; echo "${C_VERDE}>> $*${C_RESET}"; }
observe() { echo "${C_AMARELO}   [OBSERVE NOS LOGS] $*${C_RESET}"; }
aviso()   { echo "${C_VERMELHO}!! $*${C_RESET}"; }
pausa()   { echo "${C_AMARELO}   (aguardando ${1}s)${C_RESET}"; sleep "$1"; }

compose() { (cd "$RAIZ" && docker compose "$@"); }

# "fake" quando os containers fake-ieducar/fake-moodle estão rodando; senão "real"
modo_sistemas() {
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'fake-ieducar'; then echo fake; else echo real; fi
}

json() {
  if command -v jq >/dev/null 2>&1; then jq . 2>/dev/null || cat; else cat; fi
}

# GET/POST simples que não derruba o script em caso de erro
http_get()  { curl -sS -m "${2:-30}" "$1" 2>&1; }
http_post() {
  local args=(-sS -m "${3:-120}" -X POST -H 'Content-Type: application/json')
  [ -n "${2:-}" ] && args+=(-d "$2")
  curl "${args[@]}" "$1" 2>&1
}

# quantidade de mensagens em uma fila do RabbitMQ
fila_qtd() {
  local r
  r="$(curl -sS -m 10 -u "$RABBIT_AUTH" "$RABBIT_API/queues/%2F/$1" 2>/dev/null)"
  if command -v jq >/dev/null 2>&1; then echo "$r" | jq -r '.messages // "n/a"' 2>/dev/null || echo "n/a"
  else echo "$r" | grep -o '"messages":[0-9]*' | head -1 | cut -d: -f2 || echo "n/a"; fi
}

mostra_filas() {
  echo "   filas do RabbitMQ (mensagens): " \
    "principal=$(fila_qtd publicador-moodle.comandos)" \
    "retry-5s=$(fila_qtd publicador-moodle.comandos.retry-5s)" \
    "retry-30s=$(fila_qtd publicador-moodle.comandos.retry-30s)" \
    "dlq=$(fila_qtd publicador-moodle.comandos.dlq)" \
    "| sincronizador.dlq=$(fila_qtd sincronizador.turma-alterada.dlq)"
}

esperar_http() { # url, segundos
  local i; for ((i=0; i<${2:-120}; i+=3)); do curl -fsS -m 3 "$1" >/dev/null 2>&1 && return 0; sleep 3; done; return 1
}

esperar_saudavel() { # container-service, segundos
  local i st; for ((i=0; i<${2:-180}; i+=3)); do
    st="$(docker inspect -f '{{.State.Health.Status}}' "${PROJETO}-$1-1" 2>/dev/null)"
    [ "$st" = "healthy" ] && return 0; sleep 3; done; return 1
}

# ---- iEducar (fake ou real) ----
ieducar_derrubar() {
  if [ "$(modo_sistemas)" = fake ]; then
    http_get "http://localhost:${IEDUCAR_PORT}/__admin/down?seconds=0" >/dev/null; echo "   (fake) iEducar derrubado: responde 503"
  else compose stop ieducar; fi
}
ieducar_subir() {
  if [ "$(modo_sistemas)" = fake ]; then
    http_get "http://localhost:${IEDUCAR_PORT}/__admin/up" >/dev/null; echo "   (fake) iEducar de volta"
  else compose start ieducar; esperar_http "http://localhost:${IEDUCAR_PORT}/api/version" 240 || aviso "iEducar demorou para responder"; fi
}
# renomeia uma turma no iEducar (gera uma mudança para o monitor detectar). Uso: ieducar_renomear_turma "Novo nome"
ieducar_renomear_turma() {
  local nome="$1" id
  if [ "$(modo_sistemas)" = fake ]; then
    id=3; http_get "http://localhost:${IEDUCAR_PORT}/__admin/renomear-turma?id=${id}&nome=$(echo "$nome" | sed 's/ /+/g')" >/dev/null
  else
    id="$(compose exec -T ieducar-db psql -U ieducar -d ieducar -tAc "SELECT cod_turma FROM pmieducar.turma WHERE ativo=1 ORDER BY cod_turma LIMIT 1" | tr -d '\r[:space:]')"
    [ -z "$id" ] && { aviso "nenhuma turma ativa no iEducar (rode o demo-seed do T1: docker compose exec ieducar demo-seed base)"; return 1; }
    compose exec -T ieducar-db psql -U ieducar -d ieducar -c "UPDATE pmieducar.turma SET nm_turma='${nome}' WHERE cod_turma=${id}" >/dev/null
  fi
  echo "   turma ${id} do iEducar renomeada para '${nome}'"
}

nova_uuid() {
  if [ -r /proc/sys/kernel/random/uuid ]; then cat /proc/sys/kernel/random/uuid
  elif command -v uuidgen >/dev/null 2>&1; then uuidgen | tr 'A-Z' 'a-z'
  else python3 -c 'import uuid;print(uuid.uuid4())'; fi
}

# publica uma mensagem no RabbitMQ pela API HTTP de management. Uso: rabbit_publicar exchange routing_key message_id type payload_string
rabbit_publicar() {
  local ex="$1" rk="$2" mid="$3" tipo="$4" payload="$5" esc
  esc="$(printf '%s' "$payload" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  curl -sS -m 20 -u "$RABBIT_AUTH" -H 'Content-Type: application/json' -X POST \
    -d "{\"properties\":{\"message_id\":\"${mid}\",\"type\":\"${tipo}\",\"correlation_id\":\"${mid}\",\"content_type\":\"application/json\",\"delivery_mode\":2},\"routing_key\":\"${rk}\",\"payload\":\"${esc}\",\"payload_encoding\":\"string\"}" \
    "$RABBIT_API/exchanges/%2F/${ex}/publish"
  echo
}
