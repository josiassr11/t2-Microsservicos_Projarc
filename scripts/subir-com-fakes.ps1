<#
.SYNOPSIS
  Sobe TODO o T2 com iEducar e Moodle FALSOS (tools\fakes\fake_sistemas.py): em ~1-2 min, sem esperar os sistemas reais.
.DESCRIPTION
  Os fakes entram na rede do compose com os aliases "ieducar" e "moodle", então IEDUCAR_URL=http://ieducar e
  MOODLE_URL=http://moodle:8080 funcionam sem mudar nada. Portas no host: iEducar fake 8080, Moodle fake 8081.
.EXAMPLE
  .\scripts\subir-com-fakes.ps1
  .\scripts\subir-com-fakes.ps1 -Parar      # remove os containers fake
#>
param([switch]$Parar)
. "$PSScriptRoot\_comum.ps1"

if ($Parar) {
    & docker rm -f fake-ieducar fake-moodle 2>$null | Out-Null
    Write-Host 'fakes removidos'
    return
}

Write-Titulo 'T2 com sistemas FAKES'
Write-Passo '1/4  Infraestrutura (RabbitMQ, 3 bancos, Jaeger, Prometheus, Grafana)'
Invoke-Compose up -d --no-deps --wait --wait-timeout 240 rabbitmq monitor-db sincronizador-db publicador-db jaeger prometheus grafana
if ($LASTEXITCODE -ne 0) { Write-Aviso 'falha ao subir a infraestrutura'; return }

Write-Passo '2/4  iEducar e Moodle fakes (python:3.13-alpine montando tools\fakes)'
& docker rm -f fake-ieducar fake-moodle 2>$null | Out-Null
$fakes = Join-Path $script:Raiz 'tools\fakes'
& docker run -d --name fake-ieducar --network $script:Rede --network-alias ieducar -p "$($script:IEducarPort):80" `
    -v "${fakes}:/fakes:ro" python:3.13-alpine python -u /fakes/fake_sistemas.py --so ieducar --ieducar-port 80 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Aviso 'falha ao subir o iEducar fake'; return }
& docker run -d --name fake-moodle --network $script:Rede --network-alias moodle -p "$($script:MoodlePort):8080" `
    -v "${fakes}:/fakes:ro" python:3.13-alpine python -u /fakes/fake_sistemas.py --so moodle --moodle-port 8080 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Aviso 'falha ao subir o Moodle fake'; return }
if (Wait-Http "http://localhost:$($script:IEducarPort)/api/version" 60) { Write-Host "   iEducar fake no ar  -> http://localhost:$($script:IEducarPort)" } else { Write-Aviso 'iEducar fake não respondeu' }
if (Wait-Http "http://localhost:$($script:MoodlePort)/login/index.php" 60) { Write-Host "   Moodle fake no ar   -> http://localhost:$($script:MoodlePort)" } else { Write-Aviso 'Moodle fake não respondeu' }

Write-Passo '3/4  Microsserviços (build das imagens; a 1ª vez demora por causa do Maven)'
Invoke-Compose up -d --build --no-deps --wait --wait-timeout 420 sincronizador publicador-moodle
if ($LASTEXITCODE -ne 0) { Write-Aviso 'falha ao subir sincronizador/publicador-moodle'; return }
Invoke-Compose up -d --build --no-deps --wait --wait-timeout 420 monitor-ieducar
if ($LASTEXITCODE -ne 0) { Write-Aviso 'falha ao subir o monitor-ieducar'; return }

Write-Passo '4/4  Pronto'
@"
   RabbitMQ ........ http://localhost:15672  (guest/guest)
   Grafana ......... http://localhost:3000   (dashboard já provisionado)
   Jaeger .......... http://localhost:16686
   monitor ......... http://localhost:$($script:MonitorPort)/api/varreduras/ultima
   sincronizador ... http://localhost:$($script:SincronizadorPort)/api/estado
   publicador ...... http://localhost:$($script:PublicadorPort)/api/comandos
   Admin dos fakes . http://localhost:$($script:IEducarPort)/__admin/estado   e   http://localhost:$($script:MoodlePort)/__admin/estado

   Acompanhe a conversa:  .\scripts\acompanhar.ps1      Dispare uma varredura:  Invoke-RestMethod -Method Post http://localhost:$($script:MonitorPort)/api/varreduras
"@ | Write-Host
