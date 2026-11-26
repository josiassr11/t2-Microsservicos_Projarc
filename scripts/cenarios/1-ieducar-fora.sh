#!/usr/bin/env bash
# CENÁRIO 1 - iEducar fora do ar (monitor-ieducar: timeout + retry + circuit breaker)
source "$(dirname "${BASH_SOURCE[0]}")/../_comum.sh"
MON="http://localhost:${MONITOR_PORT}"

titulo "CENÁRIO 1: iEducar fora do ar  (modo: $(modo_sistemas))"
echo "Mostra que uma falha do iEducar NÃO derruba a integração: o monitor tenta de novo (retry), abre o circuito"
echo "(circuit breaker) e para de martelar o sistema doente; quando o iEducar volta, tudo se recupera sozinho."
echo "Dica: em outro terminal rode  ./scripts/acompanhar.sh"

passo "1. Varredura normal (referência)"
http_post "$MON/api/varreduras" | json
observe "[VARREDURA] ... [HTTP-OUT] iEducar GET /api/school-class ... 200 ... (sem mudanças: nada é publicado)"

passo "2. Derrubando o iEducar"
ieducar_derrubar

passo "3. Disparando varreduras com o iEducar fora (3x)"
for i in 1 2 3; do
  echo "   -- varredura $i"; http_post "$MON/api/varreduras" | json; sleep 2
done
observe "[HTTP-OUT] erro/timeout -> [RETRY] tentativa 2/3, 3/3 com espera crescente (500ms, 1s...)"
observe "[CIRCUITO] circuito 'ieducar' passou para ABERTO; as próximas varreduras falham NA HORA (chamada barrada), sem tocar no iEducar"
observe "Nenhuma mensagem [PUBLICA] é gerada: sem dados confiáveis, nada é enviado ao RabbitMQ"

passo "4. Religando o iEducar"
ieducar_subir

passo "5. Varreduras até o circuito fechar (o circuito fica ~20s aberto, depois testa em 'meio-aberto')"
for i in $(seq 1 8); do
  r="$(http_post "$MON/api/varreduras")"; echo "   -- tentativa $i: $r" | head -c 300; echo
  echo "$r" | grep -q '"status":"SUCESSO"\|"status" *: *"SUCESSO"' && break
  sleep 5
done
observe "[CIRCUITO] ABERTO -> MEIO-ABERTO (poucas chamadas de teste) -> FECHADO"
observe "[VARREDURA] concluída normalmente: o monitor se recuperou sozinho"
titulo "Fim do cenário 1"
