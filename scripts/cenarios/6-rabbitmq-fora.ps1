# CENÁRIO 6 (extra) - RabbitMQ fora do ar (monitor: publisher confirm; snapshot só avança depois da confirmação)
. "$PSScriptRoot\..\_comum.ps1"
$Mon = "http://localhost:$($script:MonitorPort)"

Write-Titulo "CENÁRIO 6: RabbitMQ fora do ar  (iEducar: $(Get-ModoSistemas))"
Write-Host "O monitor só considera uma mudança 'entregue' depois que o broker CONFIRMA o recebimento. Se o RabbitMQ"
Write-Host 'está fora, a publicação falha, o snapshot NÃO avança e a mudança é republicada quando o broker volta.'
Write-Host 'Dica: em outro terminal rode  .\scripts\acompanhar.ps1'

Write-Passo '1. Snapshots atuais do monitor (guarde o hash da turma)'
Show-Json (Invoke-Get "$Mon/api/snapshots")

Write-Passo '2. Derrubando o RabbitMQ'
Invoke-Compose stop rabbitmq
Write-Observe 'sincronizador/publicador: erros de conexão com o broker e tentativas automáticas de reconexão'

Write-Passo '3. Mudando algo no iEducar e disparando a varredura'
Rename-TurmaIEducar ("Turma Cenario Rabbit " + (Get-Date -Format 'HHmmss')) | Out-Null
Show-Json (Invoke-Post "$Mon/api/varreduras" '' 90)
Write-Observe 'monitor: [VARREDURA] 1 turma alterada -> [PUBLICA] FALHA ao publicar (sem confirmação do broker)'
Write-Observe 'monitor: snapshot NÃO atualizado (a mudança continua pendente)'

Write-Passo '4. Snapshots depois da falha: o hash da turma deve ser o MESMO de antes'
Show-Json (Invoke-Get "$Mon/api/snapshots")

Write-Passo '5. Religando o RabbitMQ'
Invoke-Compose start rabbitmq
if (Wait-Saudavel 'rabbitmq' 120) { Write-Host '   RabbitMQ saudável' } else { Write-Aviso 'RabbitMQ demorou' }
Wait-Pausa 10
Write-Observe 'sincronizador/publicador: [RECEBE] voltam a consumir (reconexão automática; filas duráveis foram preservadas)'

Write-Passo '6. Nova varredura: a mudança pendente é republicada e agora confirmada'
Show-Json (Invoke-Post "$Mon/api/varreduras" '' 90)
Write-Observe 'monitor: [PUBLICA] TurmaAlterada.v1 confirmado pelo broker -> snapshot atualizado'
Write-Observe 'sincronizador e publicador: o fluxo completo roda e a turma chega ao Moodle'
Wait-Pausa 8
Show-Json (Invoke-Get "$Mon/api/snapshots")
Write-Titulo 'Fim do cenário 6'
