# CENÁRIO 5 (extra) - Mensagem duplicada (consumidor idempotente no sincronizador)
. "$PSScriptRoot\..\_comum.ps1"
$Sinc = "http://localhost:$($script:SincronizadorPort)"

Write-Titulo 'CENÁRIO 5: mensagem duplicada'
Write-Host "O RabbitMQ garante entrega 'pelo menos uma vez': a mesma mensagem pode chegar duas vezes. Publicamos o MESMO"
Write-Host 'envelope (mesmo eventId/message_id) duas vezes direto no exchange ieducar.eventos, pela API HTTP do RabbitMQ.'
Write-Host 'Dica: em outro terminal rode  .\scripts\acompanhar.ps1'

$evento = [guid]::NewGuid().ToString()
$agora = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$envelope = @{
    eventId = $evento; tipo = 'TurmaAlterada'; versao = 1; ocorridoEm = $agora; correlationId = $evento; origem = 'script-cenario-5'
    dados = @{
        mudanca = 'CRIADA'; hash = 'demo-duplicidade'
        turma = @{ id = 9001; nome = 'Turma Duplicidade'; ano = 2026; ativa = $true; escolaId = 1; escolaNome = 'Escola Demo'; cursoId = 9001; cursoNome = 'Curso Demo Duplicidade' }
        alunos = @(@{ id = 9001; nome = 'Aluno Duplicidade'; email = $null; ativo = $true })
        professores = @()
    }
} | ConvertTo-Json -Depth 8 -Compress

Write-Passo "1. Publicando o evento $evento (1a vez)"
Publish-Rabbit 'ieducar.eventos' 'turma.alterada' $evento 'TurmaAlterada.v1' $envelope
Start-Sleep -Seconds 4
Write-Passo '2. Publicando o MESMO evento de novo (2a vez)'
Publish-Rabbit 'ieducar.eventos' 'turma.alterada' $evento 'TurmaAlterada.v1' $envelope
Start-Sleep -Seconds 4
Write-Observe 'sincronizador: 1a vez -> [RECEBE] ... [PROCESSA] ... [PUBLICA] SincronizarTurma.v1'
Write-Observe "sincronizador: 2a vez -> [RECEBE] ... [IDEMPOTENCIA] evento $evento já processado: descartado (sem novo comando)"

Write-Passo "3. Estado da turma 9001 no sincronizador: 'versao' deve ser 1 (e não 2)"
Show-Json (Invoke-Get "$Sinc/api/estado/9001")
Write-Host ''
Write-Host "   (a turma de teste 9001 cria um curso 'Curso Demo Duplicidade' no Moodle; é só dado de demonstração)"
Write-Titulo 'Fim do cenário 5'
