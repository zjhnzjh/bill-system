$ErrorActionPreference = 'Stop'
$webUrl = 'http://127.0.0.1:3100'
$orderUrl = 'http://127.0.0.1:8785'
$inventoryUrl = 'http://127.0.0.1:8786'
$paymentUrl = 'http://127.0.0.1:8787'
$sku = 'LIFE-DEMO-001'
$operator = 'v0.2-acceptance'

function Assert-Equal([object]$Expected, [object]$Actual, [string]$Message) {
    if ($Expected -ne $Actual) { throw "ASSERT FAILED: $Message (expected=$Expected, actual=$Actual)" }
    Write-Host "  PASS  $Message" -ForegroundColor Green
}

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw "ASSERT FAILED: $Message" }
    Write-Host "  PASS  $Message" -ForegroundColor Green
}

function Post-Json([string]$Uri, [hashtable]$Body) {
    Invoke-RestMethod -Method Post -Uri $Uri -ContentType 'application/json' -Body ($Body | ConvertTo-Json)
}

function New-AcceptanceOrder([string]$Name) {
    $headers = @{ 'Idempotency-Key' = "v02-$Name-$([guid]::NewGuid().ToString('N'))"; 'X-Trace-Id' = "v02-trace-$Name-$([guid]::NewGuid().ToString('N'))" }
    $body = @{ userId = 'v0.2-user'; sku = $sku; quantity = 1; amount = 39.90 } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$orderUrl/api/orders" -Headers $headers -ContentType 'application/json' -Body $body
}

Write-Host 'Bill System v0.2 integration acceptance' -ForegroundColor Cyan

Get-Content -Raw -LiteralPath "$PSScriptRoot/reset-demo-data.sql" |
    docker exec -i bill-system-mysql-1 mysql -ubill -pbill_dev_only
if ($LASTEXITCODE -ne 0) { throw 'Could not reset the v0.2 test database' }
Post-Json "$inventoryUrl/api/inventory/faults" @{ failNext = 0; delayNextMs = 0 } | Out-Null
Post-Json "$paymentUrl/api/payments/faults" @{ failNextQueries = 0; failNextCalls = 0 } | Out-Null
Invoke-RestMethod -Method Post -Uri "$orderUrl/api/reliability/circuit-breakers/payment/reset" | Out-Null

Write-Host "`nGate 1: three routes and three services" -ForegroundColor Cyan
foreach ($route in @('client', 'operations', 'database')) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$webUrl/$route"
    Assert-Equal 200 $response.StatusCode "/$route is independently addressable"
}
foreach ($port in @(8785, 8786, 8787)) {
    $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health"
    Assert-Equal 'UP' $health.status "service on port $port is healthy"
}

Write-Host "`nGate 2: WebSocket refresh channel" -ForegroundColor Cyan
$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$timeout = [System.Threading.CancellationTokenSource]::new(5000)
$null = $socket.ConnectAsync([Uri]'ws://127.0.0.1:3100/ws/events', $timeout.Token).GetAwaiter().GetResult()
$buffer = New-Object byte[] 1024
$segment = [ArraySegment[byte]]::new($buffer)
$received = $socket.ReceiveAsync($segment, $timeout.Token).GetAwaiter().GetResult()
$payload = [Text.Encoding]::UTF8.GetString($buffer, 0, $received.Count)
Assert-True ($payload -match 'STATE_REFRESH') 'WebSocket pushes a live refresh event'
$socket.Dispose()
$timeout.Dispose()

Write-Host "`nGate 3: real stock change, failed order, and audit" -ForegroundColor Cyan
$zero = Post-Json "$inventoryUrl/api/inventory/admin/items/$sku/stock" @{ available = 0; operator = $operator }
Assert-Equal 0 $zero.available 'admin API writes available=0 to inventory'
$failed = New-AcceptanceOrder 'zero-stock'
Assert-Equal 'FAILED' $failed.status 'client order fails when database stock is zero'
Assert-True ($failed.failureReason -match 'stock|409|Conflict') 'failed order preserves an explainable inventory reason'
$failedTrace = Invoke-RestMethod "$orderUrl/api/orders/$($failed.id)/trace"
Assert-True (@($failedTrace | Where-Object { $_.operation -eq 'inventory.reserve' -and $_.outcome -eq 'FAILED' }).Count -eq 1) 'Trace locates the failure at inventory.reserve'
$paymentsAfterFailure = @((Invoke-RestMethod "$paymentUrl/api/payments/all") | Where-Object { $null -ne $_.id })
Assert-Equal 0 $paymentsAfterFailure.Count 'stock failure does not create a payment record'
$inventoryAudit = @(Invoke-RestMethod "$inventoryUrl/api/inventory/admin/audits")
Assert-True (@($inventoryAudit | Where-Object { $_.action -eq 'SET_AVAILABLE' -and $_.operatorName -eq $operator }).Count -ge 1) 'stock change is persisted in the audit table'

Write-Host "`nGate 4: payment fact divergence and reconciliation" -ForegroundColor Cyan
Post-Json "$inventoryUrl/api/inventory/admin/items/$sku/stock" @{ available = 10; operator = $operator } | Out-Null
$pending = New-AcceptanceOrder 'payment-fact'
Assert-Equal 'PENDING_PAYMENT' $pending.status 'healthy order reaches PENDING_PAYMENT'
$reservation = @((Invoke-RestMethod "$inventoryUrl/api/inventory/reservations") | Where-Object { $_.orderId -eq $pending.id })
Assert-Equal 1 $reservation.Count 'inventory reservation is a real persisted record'
$payment = Invoke-RestMethod "$paymentUrl/api/payments/orders/$($pending.id)"
Assert-Equal 'PENDING' $payment.status 'payment starts as PENDING in its own schema'
$succeeded = Post-Json "$paymentUrl/api/payments/$($payment.id)/admin-status" @{ status = 'SUCCEEDED'; operator = $operator }
Assert-Equal 'SUCCEEDED' $succeeded.status 'admin API advances the payment fact'
$stillPending = Invoke-RestMethod "$orderUrl/api/orders/$($pending.id)"
Assert-Equal 'PENDING_PAYMENT' $stillPending.status 'order does not change through a cross-database shortcut'
$paid = Invoke-RestMethod -Method Post -Uri "$orderUrl/api/orders/$($pending.id)/reconcile"
Assert-Equal 'PAID' $paid.status 'reconciliation repairs the temporary inconsistency'
$paidTrace = Invoke-RestMethod "$orderUrl/api/orders/$($pending.id)/trace"
Assert-True (@($paidTrace | Where-Object { $_.operation -eq 'payment.reconcile' }).Count -ge 1) 'Trace records payment.reconcile evidence'
$paymentAudit = @(Invoke-RestMethod "$paymentUrl/api/payments/admin/audits")
Assert-True (@($paymentAudit | Where-Object { $_.action -eq 'ADMIN_STATUS' -and $_.operatorName -eq $operator }).Count -ge 1) 'payment change is persisted in the audit table'

Write-Host "`nGate 5: guarded delete, restore, and three-view data APIs" -ForegroundColor Cyan
$deleteBlocked = 0
try { Post-Json "$inventoryUrl/api/inventory/admin/items/$sku/delete" @{ operator = $operator } | Out-Null } catch { $deleteBlocked = [int]$_.Exception.Response.StatusCode }
Assert-Equal 400 $deleteBlocked 'active reservation prevents destructive item deletion'
$refunded = Invoke-RestMethod -Method Post -Uri "$orderUrl/api/orders/$($pending.id)/refund"
Assert-Equal 'REFUNDED' $refunded.status 'refund releases the active reservation'
$regressionBlocked = 0
try { Post-Json "$paymentUrl/api/payments/$($payment.id)/admin-status" @{ status = 'SUCCEEDED'; operator = $operator } | Out-Null } catch { $regressionBlocked = [int]$_.Exception.Response.StatusCode }
Assert-Equal 400 $regressionBlocked 'admin API cannot regress a refunded payment to succeeded'
$deleted = Post-Json "$inventoryUrl/api/inventory/admin/items/$sku/delete" @{ operator = $operator }
Assert-Equal $true $deleted.deleted 'item deletion changes the real inventory table'
$items = @(Invoke-RestMethod "$inventoryUrl/api/inventory/items")
Assert-Equal 0 @($items | Where-Object { $_.sku -eq $sku }).Count 'deleted item disappears from the database view API'
$missing = New-AcceptanceOrder 'missing-sku'
Assert-Equal 'FAILED' $missing.status 'client order fails after the item row is deleted'
$restored = Post-Json "$inventoryUrl/api/inventory/admin/items/$sku/restore" @{ available = 100; operator = $operator }
Assert-Equal 100 $restored.available 'restore recreates the item with usable stock'
$recovered = New-AcceptanceOrder 'restored-sku'
Assert-Equal 'PENDING_PAYMENT' $recovered.status 'a new order succeeds after item restoration'
$jobsResponse = Invoke-WebRequest -UseBasicParsing "$orderUrl/api/orders/jobs"
$orders = @((Invoke-RestMethod "$orderUrl/api/orders") | Where-Object { $null -ne $_.id })
$payments = @((Invoke-RestMethod "$paymentUrl/api/payments/all") | Where-Object { $null -ne $_.id })
Assert-True ($orders.Count -ge 4 -and $payments.Count -ge 2 -and $jobsResponse.StatusCode -eq 200) 'database view APIs return real order, payment, and job collections'

Write-Host "`nGate 6: clean reset remains safe" -ForegroundColor Cyan
Get-Content -Raw -LiteralPath "$PSScriptRoot/reset-demo-data.sql" |
    docker exec -i bill-system-mysql-1 mysql -ubill -pbill_dev_only
if ($LASTEXITCODE -ne 0) { throw 'Could not clean the v0.2 acceptance data' }
Post-Json "$inventoryUrl/api/inventory/faults" @{ failNext = 0; delayNextMs = 0 } | Out-Null
Post-Json "$paymentUrl/api/payments/faults" @{ failNextQueries = 0; failNextCalls = 0 } | Out-Null
Invoke-RestMethod -Method Post -Uri "$orderUrl/api/reliability/circuit-breakers/payment/reset" | Out-Null
$cleanOrders = @((Invoke-RestMethod "$orderUrl/api/orders") | Where-Object { $null -ne $_.id })
$cleanItem = Invoke-RestMethod "$inventoryUrl/api/inventory/items/$sku"
Assert-Equal 0 $cleanOrders.Count 'acceptance orders are removed after verification'
Assert-Equal 100 $cleanItem.available 'demo stock is restored to 100'

Write-Host "`nAll Bill System v0.2 integration gates passed; environment is clean." -ForegroundColor Green
