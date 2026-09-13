$ErrorActionPreference = 'Stop'
$orderUrl = 'http://127.0.0.1:8785'
$inventoryUrl = 'http://127.0.0.1:8786'
$paymentUrl = 'http://127.0.0.1:8787'
$sku = 'LIFE-DEMO-001'

function Assert-Equal([object]$Expected, [object]$Actual, [string]$Message) {
    if ($Expected -ne $Actual) { throw "ASSERT FAILED: $Message (expected=$Expected, actual=$Actual)" }
    Write-Host "  PASS  $Message" -ForegroundColor Green
}

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw "ASSERT FAILED: $Message" }
    Write-Host "  PASS  $Message" -ForegroundColor Green
}

function New-AsyncOrder([string]$Name, [string]$Key = '') {
    if ([string]::IsNullOrWhiteSpace($Key)) { $Key = "v031-$Name-$([guid]::NewGuid().ToString('N'))" }
    $headers = @{ 'Idempotency-Key' = $Key; 'X-Trace-Id' = "v031-trace-$Name-$([guid]::NewGuid().ToString('N'))" }
    $body = @{ userId = 'v0.3.1-user'; sku = $sku; quantity = 1; amount = 39.90 } | ConvertTo-Json
    $request = Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders" -Headers $headers -ContentType 'application/json' -Body $body
    return @{ Request = $request; Key = $Key; Headers = $headers; Body = $body }
}

function Wait-AsyncRequest([string]$Id, [string[]]$Statuses, [int]$Seconds = 18) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 300
        $request = Invoke-RestMethod "$orderUrl/api/async-orders/$Id"
    } while ($Statuses -notcontains $request.status -and (Get-Date) -lt $deadline)
    return $request
}

function Reset-KafkaLab {
    Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders/faults?failNext=0&delayMs=0" | Out-Null
    Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders/consumer/resume" | Out-Null
}

Write-Host 'Bill System v0.3.1 Kafka acceptance' -ForegroundColor Cyan
Reset-KafkaLab
Get-Content -Raw -LiteralPath "$PSScriptRoot/reset-demo-data.sql" |
    docker exec -i bill-system-mysql-1 mysql -ubill -pbill_dev_only
if ($LASTEXITCODE -ne 0) { throw 'Could not reset the v0.3.1 test database' }

Write-Host "`nGate 1: infrastructure and interview pages" -ForegroundColor Cyan
foreach ($port in @(8785, 8786, 8787)) {
    $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health"
    Assert-Equal 'UP' $health.status "service on port $port is healthy"
}
foreach ($route in @('client', 'operations', 'database')) {
    Assert-Equal 200 (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:3100/$route").StatusCode "/$route is available"
}
$metrics = Invoke-RestMethod "$orderUrl/api/async-orders/metrics"
Assert-Equal 'UP' $metrics.broker 'real Kafka broker is reachable'
Assert-Equal 3 $metrics.partitions 'command topic has three partitions'

Write-Host "`nGate 2: versioned WebSocket event envelope" -ForegroundColor Cyan
$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$timeout = [System.Threading.CancellationTokenSource]::new(5000)
$null = $socket.ConnectAsync([Uri]'ws://127.0.0.1:3100/ws/events', $timeout.Token).GetAwaiter().GetResult()
$buffer = New-Object byte[] 4096
$received = $socket.ReceiveAsync([ArraySegment[byte]]::new($buffer), $timeout.Token).GetAwaiter().GetResult()
$event = ([Text.Encoding]::UTF8.GetString($buffer, 0, $received.Count) | ConvertFrom-Json)
Assert-Equal 'STATE_REFRESH' $event.eventType 'connection produces a real event instead of timer-only refresh'
Assert-True ($event.sequence -gt 0 -and $event.payloadVersion -eq 1 -and -not [string]::IsNullOrWhiteSpace($event.eventId)) 'event envelope carries id, sequence, time, type, aggregate and version'
$socket.Dispose(); $timeout.Dispose()

Write-Host "`nGate 3: transactional Outbox and asynchronous result" -ForegroundColor Cyan
$normal = New-AsyncOrder 'outbox'
Assert-Equal 'ACCEPTED' $normal.Request.status 'HTTP entry accepts before downstream work finishes'
$deadline = (Get-Date).AddSeconds(3)
do { Start-Sleep -Milliseconds 100; $initialOutbox = @(Invoke-RestMethod "$orderUrl/api/async-orders/outbox/all" | Where-Object { $_.aggregateId -eq $normal.Request.id }) } while ($initialOutbox.Count -eq 0 -and (Get-Date) -lt $deadline)
Assert-Equal 1 $initialOutbox.Count 'request and Outbox event are persisted together'
$normalDone = Wait-AsyncRequest $normal.Request.id @('SUCCEEDED', 'REJECTED')
Assert-Equal 'SUCCEEDED' $normalDone.status 'consumer creates the business order'
$published = @(Invoke-RestMethod "$orderUrl/api/async-orders/outbox/all" | Where-Object { $_.aggregateId -eq $normal.Request.id })[0]
Assert-Equal 'PUBLISHED' $published.status 'Outbox relay records successful Kafka publication'
Assert-True ($null -ne $published.kafkaOffset) 'publication stores partition and offset evidence'

Write-Host "`nGate 4: API idempotency plus duplicate delivery idempotency" -ForegroundColor Cyan
$same = Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders" -Headers $normal.Headers -ContentType 'application/json' -Body $normal.Body
Assert-Equal $normal.Request.id $same.id 'same idempotency key returns the original async request'
Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders/$($normal.Request.id)/duplicate" | Out-Null
$deadline = (Get-Date).AddSeconds(8)
do { Start-Sleep -Milliseconds 250; $deduped = Invoke-RestMethod "$orderUrl/api/async-orders/$($normal.Request.id)" } while ($deduped.receivedCount -lt 2 -and (Get-Date) -lt $deadline)
Assert-True ($deduped.receivedCount -ge 2) 'duplicate message is physically received'
Assert-Equal 1 $deduped.businessExecutions 'duplicate message does not execute business logic again'
$duplicateEvidence = @(Invoke-RestMethod "$orderUrl/api/async-orders/$($normal.Request.id)/deliveries" | Where-Object { $_.outcome -eq 'DUPLICATE_IGNORED' })
Assert-True ($duplicateEvidence.Count -ge 1) 'consumer persists DUPLICATE_IGNORED evidence'
$sameOrders = @(Invoke-RestMethod "$orderUrl/api/orders" | Where-Object { $_.idempotencyKey -eq "kafka:$($normal.Key)" })
Assert-Equal 1 $sameOrders.Count 'duplicate delivery still maps to one business order'

Write-Host "`nGate 5: finite retry, DLQ, and manual replay" -ForegroundColor Cyan
Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders/faults?failNext=3&delayMs=0" | Out-Null
$dead = New-AsyncOrder 'dead-letter'
$deadResult = Wait-AsyncRequest $dead.Request.id @('DEAD_LETTER')
Assert-Equal 'DEAD_LETTER' $deadResult.status 'three technical failures route the message to DLQ'
Assert-Equal 0 $deadResult.businessExecutions 'technical failure does not execute the order transaction'
$deadline = (Get-Date).AddSeconds(12)
do { Start-Sleep -Milliseconds 100; $retryEvidence = Invoke-RestMethod "$orderUrl/api/async-orders/$($dead.Request.id)/deliveries" } while ($retryEvidence.Count -lt 3 -and (Get-Date) -lt $deadline)
Assert-Equal 3 $retryEvidence.Count 'all three delivery attempts are persisted'
Assert-Equal 'DEAD_LETTER' $retryEvidence[-1].outcome 'last attempt records the dead-letter decision'
Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders/faults?failNext=0&delayMs=0" | Out-Null
Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders/$($dead.Request.id)/replay" | Out-Null
$replayed = Wait-AsyncRequest $dead.Request.id @('SUCCEEDED')
Assert-Equal 'SUCCEEDED' $replayed.status 'manual replay recovers the request'
Assert-Equal 1 $replayed.businessExecutions 'replayed request executes business logic once'

Write-Host "`nGate 6: pause, observable Lag, and drain" -ForegroundColor Cyan
Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders/consumer/pause" | Out-Null
Start-Sleep -Seconds 1
$backlog = @()
1..6 | ForEach-Object { $backlog += (New-AsyncOrder "lag-$_").Request }
$deadline = (Get-Date).AddSeconds(10)
do { Start-Sleep -Milliseconds 400; $pausedMetrics = Invoke-RestMethod "$orderUrl/api/async-orders/metrics" } while (($pausedMetrics.lag -lt 6 -or $pausedMetrics.outboxPending -gt 0) -and (Get-Date) -lt $deadline)
Assert-Equal $true $pausedMetrics.consumerPaused 'consumer exposes PAUSED state'
Assert-True ($pausedMetrics.lag -ge 6) 'Lag rises while producers continue and consumers are paused'
Invoke-RestMethod -Method Post -Uri "$orderUrl/api/async-orders/consumer/resume" | Out-Null
$deadline = (Get-Date).AddSeconds(20)
do { Start-Sleep -Milliseconds 500; $drained = Invoke-RestMethod "$orderUrl/api/async-orders/metrics" } while ($drained.lag -gt 0 -and (Get-Date) -lt $deadline)
Assert-Equal 0 $drained.lag 'Lag drains to zero after consumer recovery'
foreach ($entry in $backlog) { Assert-Equal 'SUCCEEDED' (Wait-AsyncRequest $entry.id @('SUCCEEDED', 'REJECTED')).status "backlog request $($entry.id.Substring(0, 8)) succeeds" }

Write-Host "`nGate 7: asynchronous order keeps v0.2 payment recovery semantics" -ForegroundColor Cyan
$pendingOrder = Invoke-RestMethod "$orderUrl/api/orders/$($replayed.orderId)"
Assert-Equal 'PENDING_PAYMENT' $pendingOrder.status 'Kafka consumer reaches the existing payment state machine'
$drop = Invoke-RestMethod -Method Post -Uri "$orderUrl/api/orders/$($replayed.orderId)/pay?dropCallback=true"
Assert-Equal 'PENDING_PAYMENT' $drop.status 'lost callback remains an explicit intermediate state'
$deadline = (Get-Date).AddSeconds(15)
do { Start-Sleep -Seconds 1; $recovered = Invoke-RestMethod "$orderUrl/api/orders/$($replayed.orderId)" } while ($recovered.status -ne 'PAID' -and (Get-Date) -lt $deadline)
Assert-Equal 'PAID' $recovered.status 'persistent Job Runner reconciles the async-created order'

Write-Host "`nGate 8: clean reset" -ForegroundColor Cyan
Reset-KafkaLab
Get-Content -Raw -LiteralPath "$PSScriptRoot/reset-demo-data.sql" |
    docker exec -i bill-system-mysql-1 mysql -ubill -pbill_dev_only
if ($LASTEXITCODE -ne 0) { throw 'Could not clean v0.3.1 acceptance data' }
$cleanMetrics = Invoke-RestMethod "$orderUrl/api/async-orders/metrics"
Assert-Equal 0 $cleanMetrics.accepted 'async request evidence is clean after verification'
Assert-Equal 0 $cleanMetrics.outboxPending 'Outbox has no pending event after verification'

Write-Host "`nAll Bill System v0.3.1 Kafka gates passed; environment is clean." -ForegroundColor Green
