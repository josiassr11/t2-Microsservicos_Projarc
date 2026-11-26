# CENÁRIO 2 - Moodle fora do ar (publicador-moodle: retry em filas 5s/30s -> DLQ -> reprocessamento)
. "$PSScriptRoot\..\_comum.ps1"
$Mon = "http://localhost:$($script:MonitorPort)"
$Pub = "http://localhost:$($script:PublicadorPort)"
$Sinc = "http://localhost:$($script:SincronizadorPort)"

Write-Titulo "CENÁRIO 2: Moodle fora do ar  (iEducar: $(Get-ModoSistemas))"
Write-Host 'Uma mudança no iEducar chega ao publicador-moodle, mas o Moodle está fora. O comando NÃO se perde:'
Write-Host 'vai para a fila retry-5s, depois retry-30s e, esgotadas as tentativas, para a DLQ. Quando o Moodle volta,'
Write-Host 'reprocessamos a DLQ e a turma é sincronizada. (Requer FALHA_INJETAVEL=true, que é o padrão do compose.)'
Write-Host 'Dica: em outro terminal rode  .\scripts\acompanhar.ps1'

Write-Passo '1. Simulando o Moodle indisponível no publicador-moodle'
Show-Json (Invoke-Post "$Pub/api/falhas/moodle" '{"indisponivel":true}')
Write-Host '   (alternativa sem o endpoint: docker compose stop moodle  /  docker start fake-moodle)'

Write-Passo '2. Mudando algo no iEducar e disparando a varredura'
Rename-TurmaIEducar ("Turma Cenario Moodle " + (Get-Date -Format 'HHmmss')) | Out-Null
Show-Json (Invoke-Post "$Mon/api/varreduras")
Write-Observe 'monitor:       [VARREDURA] 1 turma alterada -> [PUBLICA] TurmaAlterada.v1 (confirmado pelo broker)'
Write-Observe 'sincronizador: [RECEBE] TurmaAlterada.v1 -> [PUBLICA] SincronizarTurma.v1 (comando)'
Write-Observe 'publicador:    [RECEBE] SincronizarTurma.v1 -> [HTTP-OUT] Moodle ... falhou -> [RETRY] -> [CIRCUITO]'
Write-Observe 'publicador:    [RETRY] tentativa 1 -> fila retry-5s ...; tentativa 2 -> fila retry-30s ...; tentativa 3 -> [DLQ]'
Write-Observe 'publicador:    [PUBLICA] TurmaSincronizada.v1 status=FALHA  |  sincronizador: [RESULTADO] turma em estado FALHA'

Write-Passo '3. Acompanhando as filas até o comando chegar à DLQ (leva ~40-60s)'
foreach ($i in 1..12) {
    Show-Filas
    $q = Get-FilaQtd 'publicador-moodle.comandos.dlq'
    if ($q -ne '0' -and $q -ne 'n/a') { break }
    Start-Sleep -Seconds 8
}
Write-Host '   estado da turma no sincronizador:'
Show-Json (Invoke-Get "$Sinc/api/estado")

Write-Passo '4. Moodle voltou: desligando a falha simulada'
Show-Json (Invoke-Post "$Pub/api/falhas/moodle" '{"indisponivel":false}')

Write-Passo '5. Reprocessando a DLQ'
Show-Json (Invoke-Post "$Pub/api/dlq/reprocessar")
Write-Observe 'publicador: [DLQ] reprocessando N mensagem(ns) -> [RECEBE] SincronizarTurma.v1 -> [HTTP-OUT] Moodle 200 -> [PUBLICA] TurmaSincronizada.v1 status=SUCESSO'
Write-Observe 'Se o circuito ainda estiver ABERTO (~20s após a última falha), o comando espera nas filas retry-5s/30s e é aplicado assim que o circuito fecha.'
Write-Host '   aguardando a turma voltar a SINCRONIZADA (até ~2 min)...'
foreach ($i in 1..24) {
    $est = Invoke-Get "$Sinc/api/estado"
    $txt = if ($est -is [string]) { $est } else { $est | ConvertTo-Json -Depth 10 -Compress }
    if (($txt -match '"FALHA"\s*:\s*0') -and ($txt -match '"ENVIADA"\s*:\s*0')) { break }
    Start-Sleep -Seconds 5
}
Show-Filas
Write-Host '   estado final no sincronizador:'
Show-Json (Invoke-Get "$Sinc/api/estado")
Write-Titulo 'Fim do cenário 2'
