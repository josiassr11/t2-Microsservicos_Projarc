# CENÁRIO 7 (extra) - Mensagem "veneno": corpo que não é JSON -> vai direto para a DLQ do sincronizador
. "$PSScriptRoot\..\_comum.ps1"

Write-Titulo 'CENÁRIO 7: mensagem veneno (poison message)'
Write-Host 'Uma mensagem ilegível nunca vai ser processada, por mais que tente. Ela não pode ficar voltando para a fila'
Write-Host '(travaria o consumidor): é rejeitada sem requeue e vai para a DLQ, onde fica guardada para análise.'

$antes = Get-FilaQtd 'sincronizador.turma-alterada.dlq'
Write-Passo "1. Publicando um corpo inválido em ieducar.eventos (DLQ do sincronizador antes: $antes)"
Publish-Rabbit 'ieducar.eventos' 'turma.alterada' ([guid]::NewGuid().ToString()) 'TurmaAlterada.v1' 'isto nao e json {{{'
Start-Sleep -Seconds 5
Write-Observe 'sincronizador: [RECEBE] fila=sincronizador.turma-alterada ... -> erro ao ler a mensagem -> [DLQ] rejeitada sem requeue'
Write-Host "   DLQ do sincronizador agora: $(Get-FilaQtd 'sincronizador.turma-alterada.dlq')"
Write-Host '   Para inspecionar:  http://localhost:15672  (Queues > sincronizador.turma-alterada.dlq > Get messages)'
Write-Host '   Para limpar:       curl.exe -u guest:guest -X DELETE http://localhost:15672/api/queues/%2F/sincronizador.turma-alterada.dlq/contents'
Write-Titulo 'Fim do cenário 7'
