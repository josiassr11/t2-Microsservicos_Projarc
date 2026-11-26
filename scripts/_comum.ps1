# Funções comuns dos scripts de demonstração do T2 (PowerShell 5.1+ / 7+).
# Use com:  . "$PSScriptRoot\_comum.ps1"   (nos cenários: . "$PSScriptRoot\..\_comum.ps1")
# Requer: docker (com compose v2) e curl.exe (já vem no Windows 10/11).

$script:Raiz = Split-Path -Parent $PSScriptRoot
$script:Projeto = 't2-microsservicos'
$script:Rede = "$($script:Projeto)_default"
$ErrorActionPreference = 'Continue'

function Get-EnvValor([string]$Nome, [string]$Padrao) {
    $arq = Join-Path $script:Raiz '.env'
    if (Test-Path $arq) {
        $linha = Get-Content $arq | Where-Object { $_ -match "^$Nome=" } | Select-Object -Last 1
        if ($linha) {
            $v = ($linha -split '=', 2)[1].Trim()
            if ($v) { return $v }
        }
    }
    return $Padrao
}

$script:IEducarPort = Get-EnvValor 'IEDUCAR_PORT' '8080'
$script:MoodlePort = Get-EnvValor 'MOODLE_PORT' '8081'
$script:MonitorPort = Get-EnvValor 'MONITOR_PORT' '8091'
$script:SincronizadorPort = Get-EnvValor 'SINCRONIZADOR_PORT' '8092'
$script:PublicadorPort = Get-EnvValor 'PUBLICADOR_PORT' '8093'
$script:RabbitApi = 'http://localhost:15672/api'
$script:RabbitUser = Get-EnvValor 'RABBITMQ_USER' 'guest'
$script:RabbitPass = Get-EnvValor 'RABBITMQ_PASSWORD' 'guest'

function Write-Titulo([string]$t) { Write-Host ''; Write-Host "==== $t ====" -ForegroundColor Cyan }
function Write-Passo([string]$t) { Write-Host ''; Write-Host ">> $t" -ForegroundColor Green }
function Write-Observe([string]$t) { Write-Host "   [OBSERVE NOS LOGS] $t" -ForegroundColor Yellow }
function Write-Aviso([string]$t) { Write-Host "!! $t" -ForegroundColor Red }
function Wait-Pausa([int]$s) { Write-Host "   (aguardando ${s}s)" -ForegroundColor Yellow; Start-Sleep -Seconds $s }

function Invoke-Compose {
    Push-Location $script:Raiz
    try { & docker compose @args } finally { Pop-Location }
}

# "fake" quando os containers fake-ieducar/fake-moodle estão rodando; senão "real"
function Get-ModoSistemas {
    $nomes = & docker ps --format '{{.Names}}' 2>$null
    if ($nomes -contains 'fake-ieducar') { return 'fake' } else { return 'real' }
}

function Show-Json($obj) {
    if ($null -eq $obj) { Write-Host '(sem resposta)'; return }
    if ($obj -is [string]) { Write-Host $obj; return }
    Write-Host ($obj | ConvertTo-Json -Depth 10)
}

# GET/POST que não derrubam o script em caso de erro (devolvem o objeto, ou o texto do erro)
function Invoke-Get([string]$Url, [int]$Timeout = 30) {
    try { return Invoke-RestMethod -Uri $Url -Method Get -TimeoutSec $Timeout }
    catch { return "ERRO: $($_.Exception.Message)" }
}
function Invoke-Post([string]$Url, [string]$Corpo = '', [int]$Timeout = 120) {
    try {
        if ($Corpo) { return Invoke-RestMethod -Uri $Url -Method Post -ContentType 'application/json' -Body $Corpo -TimeoutSec $Timeout }
        else { return Invoke-RestMethod -Uri $Url -Method Post -TimeoutSec $Timeout }
    }
    catch { return "ERRO: $($_.Exception.Message)" }
}

function Get-RabbitHeaders {
    $b = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$($script:RabbitUser):$($script:RabbitPass)"))
    return @{ Authorization = "Basic $b" }
}

# lista de filas (sem vhost no caminho: evita o problema do %2F no .NET Framework)
function Get-Filas {
    try { return Invoke-RestMethod -Uri "$($script:RabbitApi)/queues" -Headers (Get-RabbitHeaders) -TimeoutSec 10 }
    catch { return @() }
}

function Get-FilaQtd([string]$Nome) {
    $f = Get-Filas | Where-Object { $_.name -eq $Nome } | Select-Object -First 1
    if ($null -eq $f) { return 'n/a' }
    return [string]$f.messages
}

function Show-Filas {
    Write-Host ("   filas do RabbitMQ (mensagens): principal={0} retry-5s={1} retry-30s={2} dlq={3} | sincronizador.dlq={4}" -f `
        (Get-FilaQtd 'publicador-moodle.comandos'), (Get-FilaQtd 'publicador-moodle.comandos.retry-5s'), `
        (Get-FilaQtd 'publicador-moodle.comandos.retry-30s'), (Get-FilaQtd 'publicador-moodle.comandos.dlq'), `
        (Get-FilaQtd 'sincronizador.turma-alterada.dlq'))
}

function Wait-Http([string]$Url, [int]$Segundos = 120) {
    for ($i = 0; $i -lt $Segundos; $i += 3) {
        try { Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null; return $true } catch { Start-Sleep -Seconds 3 }
    }
    return $false
}

function Wait-Saudavel([string]$Servico, [int]$Segundos = 180) {
    for ($i = 0; $i -lt $Segundos; $i += 3) {
        $st = & docker inspect -f '{{.State.Health.Status}}' "$($script:Projeto)-$Servico-1" 2>$null
        if ($st -eq 'healthy') { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

# ---- iEducar (fake ou real) ----
function Stop-IEducar {
    if ((Get-ModoSistemas) -eq 'fake') {
        Invoke-Get "http://localhost:$($script:IEducarPort)/__admin/down?seconds=0" | Out-Null
        Write-Host '   (fake) iEducar derrubado: responde 503'
    } else { Invoke-Compose stop ieducar }
}
function Start-IEducar {
    if ((Get-ModoSistemas) -eq 'fake') {
        Invoke-Get "http://localhost:$($script:IEducarPort)/__admin/up" | Out-Null
        Write-Host '   (fake) iEducar de volta'
    } else {
        Invoke-Compose start ieducar
        if (-not (Wait-Http "http://localhost:$($script:IEducarPort)/api/version" 240)) { Write-Aviso 'iEducar demorou para responder' }
    }
}
# renomeia uma turma no iEducar (gera uma mudança para o monitor detectar)
function Rename-TurmaIEducar([string]$Nome) {
    if ((Get-ModoSistemas) -eq 'fake') {
        $id = 3
        $n = [uri]::EscapeDataString($Nome)
        Invoke-Get "http://localhost:$($script:IEducarPort)/__admin/renomear-turma?id=$id&nome=$n" | Out-Null
    } else {
        $saida = Invoke-Compose exec -T ieducar-db psql -U ieducar -d ieducar -tAc 'SELECT cod_turma FROM pmieducar.turma WHERE ativo=1 ORDER BY cod_turma LIMIT 1' | Out-String
        $id = $saida.Trim()
        if (-not $id) { Write-Aviso 'nenhuma turma ativa no iEducar (rode o demo-seed do T1: docker compose exec ieducar demo-seed base)'; return $false }
        $sql = "UPDATE pmieducar.turma SET nm_turma='$Nome' WHERE cod_turma=$id"
        Invoke-Compose exec -T ieducar-db psql -U ieducar -d ieducar -c $sql | Out-Null
    }
    Write-Host "   turma $id do iEducar renomeada para '$Nome'"
    return $true
}

# publica uma mensagem no RabbitMQ pela API HTTP de management (usa curl.exe por causa do %2F no caminho)
function Publish-Rabbit([string]$Exchange, [string]$RoutingKey, [string]$MessageId, [string]$Tipo, [string]$Payload) {
    $corpo = @{
        properties = @{ message_id = $MessageId; type = $Tipo; correlation_id = $MessageId; content_type = 'application/json'; delivery_mode = 2 }
        routing_key = $RoutingKey
        payload = $Payload
        payload_encoding = 'string'
    } | ConvertTo-Json -Depth 5 -Compress
    $tmp = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($tmp, $corpo, (New-Object Text.UTF8Encoding $false))
    $curl = if (Get-Command curl.exe -ErrorAction SilentlyContinue) { 'curl.exe' } else { 'curl' }
    & $curl -sS -m 20 -u "$($script:RabbitUser):$($script:RabbitPass)" -H 'Content-Type: application/json' -X POST --data-binary "@$tmp" "$($script:RabbitApi)/exchanges/%2F/$Exchange/publish"
    Write-Host ''
    Remove-Item $tmp -ErrorAction SilentlyContinue
}
