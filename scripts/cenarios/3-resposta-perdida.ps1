# CENÁRIO 3 (kickoff) - O Moodle CRIA o curso, mas a resposta NÃO chega ao publicador-moodle
. "$PSScriptRoot\..\_comum.ps1"
$Pub = "http://localhost:$($script:PublicadorPort)"
$Sinc = "http://localhost:$($script:SincronizadorPort)"

Write-Titulo "CENÁRIO 3: o Moodle cria o curso, mas a resposta se perde  (iEducar: $(Get-ModoSistemas))"
Write-Host "Pior caso de uma chamada de rede: o servidor EXECUTOU a operação e a resposta não voltou. Se o publicador repetisse"
Write-Host "'criar curso', o Moodle recusaria (duplicado) ou criaria outro. Aqui injetamos exatamente isso: o curso é criado no"
Write-Host "Moodle e, em seguida, o publicador 'perde' a resposta. (Requer FALHA_INJETAVEL=true, padrão do compose.)"
Write-Host 'Dica: em outro terminal rode  .\scripts\acompanhar.ps1'

function Publish-Turma($Id, $Nome, $CursoId, $CursoNome, $AlunoId, $AlunoNome) {
    $ev = [guid]::NewGuid().ToString()
    $agora = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    $env = @{
        eventId = $ev; tipo = 'TurmaAlterada'; versao = 1; ocorridoEm = $agora; correlationId = $ev; origem = 'script-cenario-3'
        dados = @{
            mudanca = 'CRIADA'; hash = "demo-$Id-$($ev.Substring(0,8))"
            turma = @{ id = $Id; nome = $Nome; ano = 2026; ativa = $true; escolaId = 1; escolaNome = 'Escola Demo'; cursoId = $CursoId; cursoNome = $CursoNome }
            alunos = @(@{ id = $AlunoId; nome = $AlunoNome; email = $null; ativo = $true })
            professores = @()
        }
    } | ConvertTo-Json -Depth 8 -Compress
    Publish-Rabbit 'ieducar.eventos' 'turma.alterada' $ev 'TurmaAlterada.v1' $env
}
function Wait-Estado($TurmaId, $Estado, [int]$Segundos = 90) {
    for ($i = 0; $i -lt $Segundos; $i += 3) {
        $r = Invoke-Get "$Sinc/api/estado/$TurmaId"
        $txt = if ($r -is [string]) { $r } else { $r | ConvertTo-Json -Depth 10 -Compress }
        if ($txt -match ('"estado"\s*:\s*"' + $Estado + '"')) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

$T = 9100000 + [int](Get-Date -Format 'HHmmss')   # ids únicos por execução
Write-Passo "1. Preparando: a categoria 'Curso Demo Resposta Perdida' já existe no Moodle (turma 9100 sem falha)"
Publish-Turma 9100 'Turma Base' 9100 'Curso Demo Resposta Perdida' 9100 'Aluno Base'
if (Wait-Estado 9100 'SINCRONIZADA' 60) { Write-Host '   turma 9100 SINCRONIZADA' } else { Write-Aviso 'turma 9100 não chegou a SINCRONIZADA' }

Write-Passo "2. Armando a falha: a próxima CRIAÇÃO no Moodle executa, mas a resposta é 'perdida'"
Show-Json (Invoke-Post "$Pub/api/falhas/moodle" '{"perderRespostaNaCriacao":1}')

Write-Passo "3. Publicando a turma $T (mesmo curso): o curso Moodle será criado e a resposta se perde"
Publish-Turma $T "Turma Resposta Perdida $T" 9100 'Curso Demo Resposta Perdida' $T "Aluno Resposta Perdida $T"
Write-Observe "publicador: [FALHA-INJETADA] Moodle executou a criação ... mas a resposta foi 'perdida' (simulado)"
Write-Observe 'publicador: [RETRY] Moodle indisponível; reenfileirado em retry-5s, tentativa 2/3  (o comando NÃO foi repetido na hora)'
Write-Observe 'publicador: 5s depois -> [RECEBE] de novo -> busca por idnumber ENCONTRA o curso -> continua e conclui (sem duplicar)'

Write-Passo '4. Aguardando a convergência (a turma deve terminar SINCRONIZADA, com 1 curso apenas)'
if (Wait-Estado $T 'SINCRONIZADA' 90) { Write-Host "   turma $T SINCRONIZADA" } else { Write-Aviso "turma $T não convergiu no prazo" }
Show-Json (Invoke-Get "$Sinc/api/estado/$T")
Write-Host ''
Write-Host "   Prova de que não duplicou: 'ultimoResumo.cursosCriados' = 0 (o curso já existia ao reexecutar)."
Write-Titulo 'Fim do cenário 3'
