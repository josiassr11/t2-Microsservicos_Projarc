#!/usr/bin/env bash
# Sobe TODO o T2 com iEducar e Moodle FALSOS (tools/fakes/fake_sistemas.py): em ~1-2 min, sem esperar os sistemas reais.
# Os fakes entram na rede do compose com os aliases "ieducar" e "moodle", então IEDUCAR_URL=http://ieducar e
# MOODLE_URL=http://moodle:8080 funcionam sem mudar nada. Portas no host: iEducar fake 8080, Moodle fake 8081.
# Uso:  ./scripts/subir-com-fakes.sh          (para derrubar os fakes: ./scripts/subir-com-fakes.sh parar)
source "$(dirname "${BASH_SOURCE[0]}")/_comum.sh"

if [ "${1:-}" = "parar" ]; then
  docker rm -f fake-ieducar fake-moodle >/dev/null 2>&1 && echo "fakes removidos"; exit 0
fi

titulo "T2 com sistemas FAKES"
passo "1/4  Infraestrutura (RabbitMQ, 3 bancos, Jaeger, Prometheus, Grafana)"
compose up -d --no-deps --wait --wait-timeout 240 rabbitmq monitor-db sincronizador-db publicador-db jaeger prometheus grafana || exit 1

passo "2/4  iEducar e Moodle fakes (python:3.13-alpine montando tools/fakes)"
docker rm -f fake-ieducar fake-moodle >/dev/null 2>&1
docker run -d --name fake-ieducar --network "$REDE" --network-alias ieducar -p "${IEDUCAR_PORT}:80" \
  -v "$RAIZ/tools/fakes:/fakes:ro" python:3.13-alpine python -u /fakes/fake_sistemas.py --so ieducar --ieducar-port 80 >/dev/null || exit 1
docker run -d --name fake-moodle --network "$REDE" --network-alias moodle -p "${MOODLE_PORT}:8080" \
  -v "$RAIZ/tools/fakes:/fakes:ro" python:3.13-alpine python -u /fakes/fake_sistemas.py --so moodle --moodle-port 8080 >/dev/null || exit 1
esperar_http "http://localhost:${IEDUCAR_PORT}/api/version" 60 && echo "   iEducar fake no ar  -> http://localhost:${IEDUCAR_PORT}" || aviso "iEducar fake não respondeu"
esperar_http "http://localhost:${MOODLE_PORT}/login/index.php" 60 && echo "   Moodle fake no ar   -> http://localhost:${MOODLE_PORT}" || aviso "Moodle fake não respondeu"

passo "3/4  Microsserviços (build das imagens; a 1ª vez demora por causa do Maven)"
compose up -d --build --no-deps --wait --wait-timeout 420 sincronizador publicador-moodle || exit 1
compose up -d --build --no-deps --wait --wait-timeout 420 monitor-ieducar || exit 1

passo "4/4  Pronto"
cat <<MSG
   RabbitMQ ........ http://localhost:15672  (guest/guest)
   Grafana ......... http://localhost:3000   (dashboard já provisionado)
   Jaeger .......... http://localhost:16686
   monitor ......... http://localhost:${MONITOR_PORT}/api/varreduras/ultima
   sincronizador ... http://localhost:${SINCRONIZADOR_PORT}/api/estado
   publicador ...... http://localhost:${PUBLICADOR_PORT}/api/comandos
   Admin dos fakes . http://localhost:${IEDUCAR_PORT}/__admin/estado   e   http://localhost:${MOODLE_PORT}/__admin/estado

   Acompanhe a conversa:  ./scripts/acompanhar.sh      Dispare uma varredura:  curl -X POST localhost:${MONITOR_PORT}/api/varreduras
MSG
