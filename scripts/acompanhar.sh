#!/usr/bin/env bash
# Acompanha, em um só fluxo e com cores, os logs dos 3 microsserviços (a "conversa" entre eles).
# Uso:  ./scripts/acompanhar.sh [-c <correlationId>] [-t <turmaId>] [-s <janela, ex.: 10m>] [-n <linhas>]
#   -c  mostra só as linhas daquele correlationId (acompanha UMA mudança de ponta a ponta)
#   -t  mostra só as linhas daquela turma (turmaId=<id>)
#   -s  mostra o histórico desde... (padrão: 5m; use -s 0s para só as linhas novas)
source "$(dirname "${BASH_SOURCE[0]}")/_comum.sh"
CORR=""; TURMA=""; DESDE="5m"
while getopts "c:t:s:h" o; do case $o in
  c) CORR="$OPTARG";; t) TURMA="$OPTARG";; s) DESDE="$OPTARG";;
  h|*) sed -n '2,7p' "$0"; exit 0;; esac; done

ESC=$'\033'
FILTRO='cat'
[ -n "$CORR" ]  && FILTRO="grep -F --line-buffered -e corr=${CORR}"
[ -n "$TURMA" ] && FILTRO="grep -E --line-buffered -e turmaId=${TURMA}([^0-9]|\$)"

echo "${C_NEGRITO}Acompanhando monitor-ieducar | sincronizador | publicador-moodle   (Ctrl+C para sair)${C_RESET}"
[ -n "$CORR" ]  && echo "filtro: correlationId=$CORR"
[ -n "$TURMA" ] && echo "filtro: turmaId=$TURMA"

SINCE=(); [ -n "$DESDE" ] && SINCE=(--since "$DESDE")
compose logs -f --no-color "${SINCE[@]}" monitor-ieducar sincronizador publicador-moodle 2>&1 | $FILTRO | sed -u -E \
  -e "s/^(monitor-ieducar +\|)/${ESC}[1;36m\1${ESC}[0m/" \
  -e "s/^(sincronizador +\|)/${ESC}[1;33m\1${ESC}[0m/" \
  -e "s/^(publicador-moodle +\|)/${ESC}[1;35m\1${ESC}[0m/" \
  -e "s/(\[PUBLICA\])/${ESC}[32m\1${ESC}[0m/g" \
  -e "s/(\[RECEBE\])/${ESC}[34m\1${ESC}[0m/g" \
  -e "s/(\[RETRY\])/${ESC}[33m\1${ESC}[0m/g" \
  -e "s/(\[CIRCUITO\])/${ESC}[1;31m\1${ESC}[0m/g" \
  -e "s/(\[DLQ\])/${ESC}[1;31m\1${ESC}[0m/g" \
  -e "s/(\[IDEMPOTENCIA\])/${ESC}[35m\1${ESC}[0m/g" \
  -e "s/(\[HTTP-OUT\])/${ESC}[36m\1${ESC}[0m/g" \
  -e "s/(\[RESULTADO\])/${ESC}[1;32m\1${ESC}[0m/g"
