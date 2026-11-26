<#
.SYNOPSIS
  Mostra o estado da integração: última varredura, estado das turmas, mapeamentos, DLQ e filas do RabbitMQ.
#>
. "$PSScriptRoot\_comum.ps1"

Write-Titulo "Última varredura do monitor-ieducar  (:$($script:MonitorPort))"
Show-Json (Invoke-Get "http://localhost:$($script:MonitorPort)/api/varreduras/ultima")
Write-Titulo "Estado das turmas no sincronizador  (:$($script:SincronizadorPort))"
Show-Json (Invoke-Get "http://localhost:$($script:SincronizadorPort)/api/estado")
Write-Titulo 'Mapeamento iEducar <-> Moodle (dono: sincronizador)'
Show-Json (Invoke-Get "http://localhost:$($script:SincronizadorPort)/api/mapeamentos")
Write-Titulo "Comandos processados pelo publicador-moodle  (:$($script:PublicadorPort))"
Show-Json (Invoke-Get "http://localhost:$($script:PublicadorPort)/api/comandos")
Write-Titulo 'DLQ do publicador'
Show-Json (Invoke-Get "http://localhost:$($script:PublicadorPort)/api/dlq")
Write-Titulo 'Filas do RabbitMQ'
Get-Filas | Select-Object @{n = 'FILA'; e = { $_.name } }, @{n = 'PRONTAS'; e = { $_.messages_ready } },
    @{n = 'SEM_ACK'; e = { $_.messages_unacknowledged } }, @{n = 'CONSUMIDORES'; e = { $_.consumers } } | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
