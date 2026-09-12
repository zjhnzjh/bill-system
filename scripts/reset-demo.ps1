$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)

Get-Content -Raw -LiteralPath '.\scripts\reset-demo-data.sql' |
    docker exec -i bill-system-mysql-1 mysql -ubill -pbill_dev_only
if ($LASTEXITCODE -ne 0) { throw 'Failed to reset demo database' }

Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8786/api/inventory/faults' -ContentType 'application/json' `
    -Body (@{ failNext = 0; delayNextMs = 0 } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8787/api/payments/faults' -ContentType 'application/json' `
    -Body (@{ failNextQueries = 0; failNextCalls = 0 } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8785/api/reliability/circuit-breakers/payment/reset' | Out-Null

Write-Host 'Demo data, fault switches, and circuit breaker were reset.' -ForegroundColor Green
