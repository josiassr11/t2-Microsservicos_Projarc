<#
.SYNOPSIS
  Acompanha, em um só fluxo e com cores, os logs dos 3 microsserviços (a "conversa" entre eles).
.EXAMPLE
  .\scripts\acompanhar.ps1
  .\scripts\acompanhar.ps1 -Corr 3f2a...     # só uma mudança, de ponta a ponta (correlationId)
  .\scripts\acompanhar.ps1 -Turma 3          # só a turma 3
  .\scripts\acompanhar.ps1 -Desde 0s         # só as linhas novas
#>
param(
    [string]$Corr = '',
    [string]$Turma = '',
    [string]$Desde = '5m'
)
. "$PSScriptRoot\_comum.ps1"

Write-Host 'Acompanhando monitor-ieducar | sincronizador | publicador-moodle   (Ctrl+C para sair)' -ForegroundColor White
if ($Corr) { Write-Host "filtro: correlationId=$Corr" }
if ($Turma) { Write-Host "filtro: turmaId=$Turma" }

function Get-CorServico([string]$prefixo) {
    if ($prefixo -like 'monitor-ieducar*') { return 'Cyan' }
    if ($prefixo -like 'sincronizador*') { return 'Yellow' }
    if ($prefixo -like 'publicador-moodle*') { return 'Magenta' }
    return 'Gray'
}
function Get-CorLinha([string]$l) {
    if ($l -match '\[CIRCUITO\]|\[DLQ\]|ERROR') { return 'Red' }
    if ($l -match '\[RETRY\]|WARN') { return 'Yellow' }
    if ($l -match '\[IDEMPOTENCIA\]') { return 'Magenta' }
    if ($l -match '\[PUBLICA\]|\[RESULTADO\]') { return 'Green' }
    if ($l -match '\[RECEBE\]') { return 'Blue' }
    if ($l -match '\[HTTP-OUT\]') { return 'DarkCyan' }
    return 'Gray'
}

$argsLogs = @('logs', '-f', '--no-color')
if ($Desde) { $argsLogs += @('--since', $Desde) }
$argsLogs += @('monitor-ieducar', 'sincronizador', 'publicador-moodle')

Push-Location $script:Raiz
try {
    & docker compose @argsLogs 2>&1 | ForEach-Object {
        $l = "$_"
        if ($Corr -and $l -notmatch [regex]::Escape("corr=$Corr")) { return }
        if ($Turma -and $l -notmatch "turmaId=$Turma(\D|$)") { return }
        $i = $l.IndexOf('|')
        if ($i -gt 0) {
            Write-Host ($l.Substring(0, $i + 1)) -ForegroundColor (Get-CorServico $l) -NoNewline
            Write-Host ($l.Substring($i + 1)) -ForegroundColor (Get-CorLinha $l)
        } else {
            Write-Host $l -ForegroundColor (Get-CorLinha $l)
        }
    }
} finally { Pop-Location }
