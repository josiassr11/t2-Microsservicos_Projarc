# CENÁRIO 4 (kickoff) - Um serviço REINICIA no meio da sincronização
. "$PSScriptRoot\..\_comum.ps1"
$Pub = "http://localhost:$($script:PublicadorPort)"
$Sinc = "http://localhost:$($script:SincronizadorPort)"

Write-Titulo "CENÁRIO 4: o publicador-moodle morre no meio da sincronização  (iEducar: $(Get-ModoSistemas))"
Write-Host 'Deixamos o publicador lento (cada chamada ao Moodle espera 1,5 s) para abrir uma janela, publicamos uma turma com'
Write-Host 'vários alunos e matamos o container (kill -9) no meio. O comando ainda não tinha sido confirmado (ack) ao broker,'
Write-Host 'então volta para a fila; ao reiniciar, o serviço o reprocessa e, como tudo é idempotente, termina sem duplicar.'
Write-Host 'Dica: em outro terminal rode  .\scripts\acompanhar.ps1'

function Wait-Estado($TurmaId, $Estado, [int]$Segundos = 90) {
    for ($i = 0; $i -lt $Segundos; $i += 3) {
        $r = Invoke-Get "$Sinc/api/estado/$TurmaId"
        $txt = if ($r -is [string]) { $r } else { $r | ConvertTo-Json -Depth 10 -Compress }
        if ($txt -match ('"estado"\s*:\s*"' + $Estado + '"')) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

Write-Passo '1. Deixando o publicador lento (latenciaMs=1500)'
Show-Json (Invoke-Post "$Pub/api/falhas/moodle" '{"latenciaMs":1500}')

$T = 9200000 + 10 * [int](Get-Date -Format 'HHmmss')   # ids únicos por execução
Write-Passo "2. Publicando a turma $T (1 curso, 4 alunos, 1 professor)"
$ev = [guid]::NewGuid().ToString()
$agora = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$alunos = @(
    @{ id = $($T + 1); nome = 'Aluno Um'; email = $null; ativo = $true }, @{ id = $($T + 2); nome = 'Aluno Dois'; email = $null; ativo = $true },
    @{ id = $($T + 3); nome = 'Aluno Tres'; email = $null; ativo = $true }, @{ id = $($T + 4); nome = 'Aluno Quatro'; email = $null; ativo = $true })
$envelope = @{
    eventId = $ev; tipo = 'TurmaAlterada'; versao = 1; ocorridoEm = $agora; correlationId = $ev; origem = 'script-cenario-4'
    dados = @{
        mudanca = 'CRIADA'; hash = "demo-$T-$($ev.Substring(0,8))"
        turma = @{ id = $T; nome = "Turma Reinicio $T"; ano = 2026; ativa = $true; escolaId = 1; escolaNome = 'Escola Demo'; cursoId = $T; cursoNome = 'Curso Demo Reinicio' }
        alunos = $alunos
        professores = @(@{ id = $($T + 9); nome = 'Professor Demo'; email = $null; ativo = $true })
    }
} | ConvertTo-Json -Depth 8 -Compress
Publish-Rabbit 'ieducar.eventos' 'turma.alterada' $ev 'TurmaAlterada.v1' $envelope
Write-Host '   aguardando 9s: o publicador já está no meio do trabalho (criando categoria, curso, usuários...)'
Start-Sleep -Seconds 9
Write-Observe 'publicador: [RECEBE] SincronizarTurma.v1 ... [HTTP-OUT] Moodle ... (lento) — e ainda SEM [RESULTADO]'

Write-Passo '3. MATANDO o publicador-moodle (docker kill)'
& docker kill "$($script:Projeto)-publicador-moodle-1" | Out-Null
Write-Host '   container publicador-moodle morto (SIGKILL)'
Show-Filas
Write-Observe 'RabbitMQ: a mensagem não confirmada (unacked) volta para a fila principal quando a conexão do serviço cai'

Write-Passo '4. Reiniciando o publicador-moodle'
& docker start "$($script:Projeto)-publicador-moodle-1" | Out-Null
if (Wait-Saudavel 'publicador-moodle' 120) { Write-Host '   publicador de volta (a latência injetada some: o estado de falhas é só em memória)' }
Write-Observe 'publicador: [RECEBE] SincronizarTurma.v1 (mesma mensagem, reentregue) -> busca por idnumber -> só cria o que faltava -> [RESULTADO] SUCESSO'

Write-Passo '5. Aguardando a convergência'
if (Wait-Estado $T 'SINCRONIZADA' 120) { Write-Host "   turma $T SINCRONIZADA" } else { Write-Aviso "turma $T não convergiu no prazo" }
Show-Json (Invoke-Get "$Sinc/api/estado/$T")
Write-Host ''
Write-Host "   Veja 'ultimoResumo': só aparecem as criações que o 2º processamento precisou fazer (o resto já existia do 1º)."
Start-Sleep -Seconds 6   # o painel de gerenciamento do RabbitMQ atualiza as contagens a cada ~5s
Show-Filas
Write-Titulo 'Fim do cenário 4'
