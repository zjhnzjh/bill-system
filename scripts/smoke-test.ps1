$ErrorActionPreference = 'Stop'
$baseUrl = 'http://127.0.0.1:8785'
$inventoryUrl = 'http://127.0.0.1:8786'
$paymentUrl = 'http://127.0.0.1:8787'
Add-Type -AssemblyName System.Net.Http

function Assert-Equal([object]$Expected, [object]$Actual, [string]$Message) {
    if ($Expected -ne $Actual) {
        throw "ASSERT FAILED: $Message (expected=$Expected, actual=$Actual)"
    }
    Write-Host "  PASS  $Message" -ForegroundColor Green
}

function New-TestOrder([string]$Suffix) {
    $key = "smoke-$Suffix-$([guid]::NewGuid().ToString('N'))"
    $trace = "trace-$Suffix-$([guid]::NewGuid().ToString('N'))"
    $headers = @{ 'Idempotency-Key' = $key; 'X-Trace-Id' = $trace }
    $body = @{ userId = "test-$Suffix"; sku = 'LIFE-DEMO-001'; quantity = 1; amount = 39.90 } | ConvertTo-Json
    $order = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders" -Headers $headers -ContentType 'application/json' -Body $body
    return @{ Order = $order; Key = $key; Trace = $trace; Headers = $headers; Body = $body }
}

Write-Host 'Bill System smoke test' -ForegroundColor Cyan

$healthPorts = @(8785, 8786, 8787)
foreach ($port in $healthPorts) {
    $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health"
    Assert-Equal 'UP' $health.status "service on port $port is healthy"
}

Write-Host "`nScenario 1: idempotent order creation" -ForegroundColor Cyan
$idem = New-TestOrder 'idem'
$replay = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders" -Headers $idem.Headers -ContentType 'application/json' -Body $idem.Body
Assert-Equal $idem.Order.id $replay.id 'same idempotency key returns the original order'

$conflictBody = @{ userId = 'changed-user'; sku = 'LIFE-DEMO-001'; quantity = 2; amount = 39.90 } | ConvertTo-Json
$conflictStatus = 0
try {
    Invoke-WebRequest -Method Post -Uri "$baseUrl/api/orders" -Headers $idem.Headers -ContentType 'application/json' -Body $conflictBody | Out-Null
} catch {
    $conflictStatus = [int]$_.Exception.Response.StatusCode
}
Assert-Equal 400 $conflictStatus 'same key with a different request is rejected'

$http = [System.Net.Http.HttpClient]::new()
$raceKey = "idem-race-$([guid]::NewGuid().ToString('N'))"
$raceBody = '{"userId":"idempotency-race","sku":"LIFE-DEMO-001","quantity":1,"amount":39.90}'
$raceTasks = @()
1..20 | ForEach-Object {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$baseUrl/api/orders")
    $request.Headers.Add('Idempotency-Key', $raceKey)
    $request.Headers.Add('X-Client-Id', "idem-race-$raceKey")
    $request.Content = [System.Net.Http.StringContent]::new($raceBody, [System.Text.Encoding]::UTF8, 'application/json')
    $raceTasks += $http.SendAsync($request)
}
[System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$raceTasks)
$raceCodes = @($raceTasks | ForEach-Object { [int]$_.Result.StatusCode })
$raceOrderIds = @($raceTasks | Where-Object { [int]$_.Result.StatusCode -eq 200 } | ForEach-Object {
    ($_.Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json).id
} | Select-Object -Unique)
Assert-Equal 0 @($raceCodes | Where-Object { $_ -ne 200 }).Count 'concurrent idempotent replays do not leak unique-index errors'
Assert-Equal 1 $raceOrderIds.Count 'twenty concurrent identical requests create one order'
$http.Dispose()

Write-Host "`nScenario 2: injected inventory failure is contained" -ForegroundColor Cyan
$stockBeforeFault = Invoke-RestMethod "$inventoryUrl/api/inventory/items/LIFE-DEMO-001"
Invoke-RestMethod -Method Post -Uri "$inventoryUrl/api/inventory/faults" -ContentType 'application/json' -Body (@{ failNext = 1; delayNextMs = 0 } | ConvertTo-Json) | Out-Null
$failedOrder = New-TestOrder 'inventory-failure'
Assert-Equal 'FAILED' $failedOrder.Order.status 'inventory failure prevents progression to payment'
$stockAfterFault = Invoke-RestMethod "$inventoryUrl/api/inventory/items/LIFE-DEMO-001"
Assert-Equal $stockBeforeFault.available $stockAfterFault.available 'failed reservation does not consume stock'
$timer = [System.Diagnostics.Stopwatch]::StartNew()
Invoke-RestMethod -Method Post -Uri "$inventoryUrl/api/inventory/faults" -ContentType 'application/json' -Body (@{ failNext = 0; delayNextMs = 2500 } | ConvertTo-Json) | Out-Null
$timeoutOrder = New-TestOrder 'inventory-timeout'
$timer.Stop()
Assert-Equal 'FAILED' $timeoutOrder.Order.status 'client timeout gives the order an explicit failure state'
if ($timer.ElapsedMilliseconds -ge 2200) {
    throw "ASSERT FAILED: timeout boundary was too slow ($($timer.ElapsedMilliseconds) ms)"
}
Write-Host "  PASS  downstream timeout returns in $($timer.ElapsedMilliseconds) ms instead of hanging" -ForegroundColor Green
Start-Sleep -Seconds 2

Write-Host "`nScenario 3: concurrent requests cannot oversell the last item" -ForegroundColor Cyan
$raceId = [guid]::NewGuid().ToString('N')
$workers = @()
1..10 | ForEach-Object {
    $reservationId = "race-$raceId-$_"
    $workers += Start-Job -ScriptBlock {
        param($url, $orderId)
        $payload = @{ orderId = $orderId; sku = 'LIFE-CONCURRENCY-001'; quantity = 1 } | ConvertTo-Json
        try {
            Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$url/api/inventory/reservations" -ContentType 'application/json' -Body $payload | Out-Null
            [pscustomobject]@{ orderId = $orderId; status = 200 }
        } catch {
            [pscustomobject]@{ orderId = $orderId; status = [int]$_.Exception.Response.StatusCode }
        }
    } -ArgumentList $inventoryUrl, $reservationId
}
$workers | Wait-Job | Out-Null
$raceResults = @($workers | Receive-Job)
$workers | Remove-Job -Force
$winners = @($raceResults | Where-Object status -eq 200)
Assert-Equal 1 $winners.Count 'exactly one contender reserves the last item'
$raceStock = Invoke-RestMethod "$inventoryUrl/api/inventory/items/LIFE-CONCURRENCY-001"
Assert-Equal 0 $raceStock.available 'available stock never becomes negative'
Invoke-RestMethod -Method Post -Uri "$inventoryUrl/api/inventory/reservations/$($winners[0].orderId)/release" | Out-Null

Write-Host "`nScenario 4: payment callback and duplicate callback" -ForegroundColor Cyan
$normal = New-TestOrder 'pay'
$paid = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/$($normal.Order.id)/pay"
Assert-Equal 'PAID' $paid.status 'normal callback advances the order to PAID'
$duplicate = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/payment-callback" -Headers @{ 'X-Trace-Id' = $normal.Trace } -ContentType 'application/json' -Body (@{ paymentId = $paid.paymentId; orderId = $paid.id; status = 'SUCCEEDED' } | ConvertTo-Json)
Assert-Equal 'PAID' $duplicate.status 'duplicate callback is idempotently ignored'

Write-Host "`nScenario 5: lost callback and Job Runner recovery" -ForegroundColor Cyan
$lost = New-TestOrder 'lost'
$pending = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/$($lost.Order.id)/pay?dropCallback=true"
Assert-Equal 'PENDING_PAYMENT' $pending.status 'dropped callback leaves a visible intermediate state'
$deadline = (Get-Date).AddSeconds(15)
do {
    Start-Sleep -Seconds 1
    $recovered = Invoke-RestMethod "$baseUrl/api/orders/$($lost.Order.id)"
} while ($recovered.status -ne 'PAID' -and (Get-Date) -lt $deadline)
Assert-Equal 'PAID' $recovered.status 'persistent Job Runner reconciles the missing callback'
$job = (Invoke-RestMethod "$baseUrl/api/orders/jobs") | Where-Object orderId -eq $lost.Order.id | Select-Object -First 1
Assert-Equal 'SUCCEEDED' $job.status 'recovery job records a successful terminal state'

Write-Host "`nScenario 6: retry, dead letter, and manual recovery" -ForegroundColor Cyan
$dead = New-TestOrder 'dead-letter'
$deadPending = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/$($dead.Order.id)/pay?dropCallback=true"
Invoke-RestMethod -Method Post -Uri "$paymentUrl/api/payments/faults" -ContentType 'application/json' -Body (@{ failNextQueries = 3 } | ConvertTo-Json) | Out-Null
$deadline = (Get-Date).AddSeconds(18)
do {
    Start-Sleep -Seconds 1
    $deadJob = (Invoke-RestMethod "$baseUrl/api/orders/jobs") | Where-Object orderId -eq $dead.Order.id | Select-Object -First 1
} while ($deadJob.status -ne 'DEAD_LETTER' -and (Get-Date) -lt $deadline)
Assert-Equal 'DEAD_LETTER' $deadJob.status 'three failed attempts move the task to dead letter'
Assert-Equal 3 $deadJob.attempts 'failed attempts are persisted'
Invoke-RestMethod -Method Post -Uri "$paymentUrl/api/payments/faults" -ContentType 'application/json' -Body (@{ failNextQueries = 0 } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/jobs/$($deadJob.id)/retry" | Out-Null
$deadline = (Get-Date).AddSeconds(8)
do {
    Start-Sleep -Seconds 1
    $deadRecovered = Invoke-RestMethod "$baseUrl/api/orders/$($dead.Order.id)"
} while ($deadRecovered.status -ne 'PAID' -and (Get-Date) -lt $deadline)
Assert-Equal 'PAID' $deadRecovered.status 'manual retry recovers the dead-letter task idempotently'

Write-Host "`nScenario 7: Redis rate limit protects the create-order entry point" -ForegroundColor Cyan
$http = [System.Net.Http.HttpClient]::new()
$rateKey = "rate-race-$([guid]::NewGuid().ToString('N'))"
$rateClient = "rate-client-$([guid]::NewGuid().ToString('N'))"
$rateBody = '{"userId":"rate-race","sku":"LIFE-DEMO-001","quantity":1,"amount":39.90}'
$rateTasks = @()
1..80 | ForEach-Object {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$baseUrl/api/orders")
    $request.Headers.Add('Idempotency-Key', $rateKey)
    $request.Headers.Add('X-Client-Id', $rateClient)
    $request.Content = [System.Net.Http.StringContent]::new($rateBody, [System.Text.Encoding]::UTF8, 'application/json')
    $rateTasks += $http.SendAsync($request)
}
[System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$rateTasks)
$rateCodes = @($rateTasks | ForEach-Object { [int]$_.Result.StatusCode })
Assert-Equal 50 @($rateCodes | Where-Object { $_ -eq 200 }).Count 'configured first fifty requests pass in the one-second window'
Assert-Equal 30 @($rateCodes | Where-Object { $_ -eq 429 }).Count 'excess requests receive an explicit HTTP 429'
$http.Dispose()

Write-Host "`nScenario 8: circuit breaker and bulkhead protect payment calls" -ForegroundColor Cyan
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/reliability/circuit-breakers/payment/reset" | Out-Null
Invoke-RestMethod -Method Post -Uri "$paymentUrl/api/payments/faults" -ContentType 'application/json' -Body (@{ failNextQueries = 0; failNextCalls = 4 } | ConvertTo-Json) | Out-Null
1..4 | ForEach-Object {
    $broken = New-TestOrder "payment-down-$_"
    Assert-Equal 'MANUAL_REVIEW' $broken.Order.status "payment failure $_ is contained and compensated"
}
$circuit = Invoke-RestMethod "$baseUrl/api/reliability/circuit-breakers/payment"
Assert-Equal 'OPEN' $circuit.state 'failure threshold opens the payment circuit'
Invoke-RestMethod -Method Post -Uri "$paymentUrl/api/payments/faults" -ContentType 'application/json' -Body (@{ failNextQueries = 0; failNextCalls = 0 } | ConvertTo-Json) | Out-Null
$shortCircuited = New-TestOrder 'payment-short-circuit'
Assert-Equal 'MANUAL_REVIEW' $shortCircuited.Order.status 'open circuit fails fast without calling payment service'
if ($shortCircuited.Order.failureReason -notmatch 'OPEN') {
    throw 'ASSERT FAILED: order does not explain that the payment circuit is OPEN'
}
Write-Host '  PASS  failure reason explains the open circuit' -ForegroundColor Green
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/reliability/circuit-breakers/payment/reset" | Out-Null

Write-Host "`nScenario 9: compensation for cancel and refund" -ForegroundColor Cyan
$before = Invoke-RestMethod "$inventoryUrl/api/inventory/items/LIFE-DEMO-001"
$cancelCase = New-TestOrder 'cancel'
$cancelled = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/$($cancelCase.Order.id)/cancel"
Assert-Equal 'CANCELLED' $cancelled.status 'unpaid cancellation reaches CANCELLED'
$afterCancel = Invoke-RestMethod "$inventoryUrl/api/inventory/items/LIFE-DEMO-001"
Assert-Equal $before.available $afterCancel.available 'cancellation releases reserved inventory'

$refundCase = New-TestOrder 'refund'
$refundPaid = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/$($refundCase.Order.id)/pay"
$refunded = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/$($refundCase.Order.id)/refund"
Assert-Equal 'REFUNDED' $refunded.status 'paid order reaches REFUNDED through compensation'
$payment = Invoke-RestMethod "$paymentUrl/api/payments/orders/$($refundCase.Order.id)"
Assert-Equal 'REFUNDED' $payment.status 'payment service and order service agree on refund state'

$trace = Invoke-RestMethod "$baseUrl/api/orders/$($lost.Order.id)/trace"
$operations = @($trace | ForEach-Object operation)
if ($operations -notcontains 'payment.reconcile') {
    throw 'ASSERT FAILED: trace does not contain payment.reconcile'
}
Write-Host '  PASS  trace contains payment.reconcile evidence' -ForegroundColor Green

Write-Host "`nAll smoke scenarios passed." -ForegroundColor Green
