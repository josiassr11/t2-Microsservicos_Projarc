# CENÁRIO 1 - iEducar fora do ar (monitor-ieducar: timeout + retry + circuit breaker)
. "$PSScriptRoot\..\_comum.ps1"
$Mon = "http://localhost:$($script:MonitorPort)"

Write-Titulo "CENÁRIO 1: iEducar fora do ar  (modo: $(Get-ModoSistemas))"
Write-Host 'Mostra que uma falha do iEducar NÃO derruba a integração: o monitor tenta de novo (retry), abre o circuito'
Write-Host '(circuit breaker) e para de martelar o sistema doente; quando o iEducar volta, tudo se recupera sozinho.'
Write-Host 'Dica: em outro terminal rode  .\scripts\acompanhar.ps1'

Write-Passo '1. Varredura normal (referência)'
Show-Json (Invoke-Post "$Mon/api/varreduras")
Write-Observe '[VARREDURA] ... [HTTP-OUT] iEducar GET /api/school-class ... 200 ... (sem mudanças: nada é publicado)'

Write-Passo '2. Derrubando o iEducar'
Stop-IEducar

Write-Passo '3. Disparando varreduras com o iEducar fora (3x)'
foreach ($i in 1..3) {
    Write-Host "   -- varredura $i"
    Show-Json (Invoke-Post "$Mon/api/varreduras")
    Start-Sleep -Seconds 2
}
Write-Observe '[HTTP-OUT] erro/timeout -> [RETRY] tentativa 2/3, 3/3 com espera crescente (500ms, 1s...)'
Write-Observe "[CIRCUITO] circuito 'ieducar' passou para ABERTO; as próximas varreduras falham NA HORA (chamada barrada), sem tocar no iEducar"
Write-Observe 'Nenhuma mensagem [PUBLICA] é gerada: sem dados confiáveis, nada é enviado ao RabbitMQ'

Write-Passo '4. Religando o iEducar'
Start-IEducar

Write-Passo "5. Varreduras até o circuito fechar (o circuito fica ~20s aberto, depois testa em 'meio-aberto')"
foreach ($i in 1..8) {
    $r = Invoke-Post "$Mon/api/varreduras"
    $txt = if ($r -is [string]) { $r } else { $r | ConvertTo-Json -Depth 10 -Compress }
    Write-Host "   -- tentativa ${i}: $txt"
    if ($txt -match '"status"\s*:\s*"SUCESSO"') { break }
    Start-Sleep -Seconds 5
}
Write-Observe '[CIRCUITO] ABERTO -> MEIO-ABERTO (poucas chamadas de teste) -> FECHADO'
Write-Observe '[VARREDURA] concluída normalmente: o monitor se recuperou sozinho'
Write-Titulo 'Fim do cenário 1'
