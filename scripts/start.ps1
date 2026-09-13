$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)

$requiredPorts = @(3100, 8785, 8786, 8787, 3306, 6379, 9092)
$requiredServices = @('mysql', 'redis', 'kafka', 'inventory-service', 'payment-service', 'order-service', 'web')

function Test-BillHealth {
    try {
        $web = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:3100/' -TimeoutSec 2
        $order = Invoke-RestMethod -Uri 'http://127.0.0.1:8785/actuator/health' -TimeoutSec 2
        $inventory = Invoke-RestMethod -Uri 'http://127.0.0.1:8786/actuator/health' -TimeoutSec 2
        $payment = Invoke-RestMethod -Uri 'http://127.0.0.1:8787/actuator/health' -TimeoutSec 2
        $kafka = Invoke-RestMethod -Uri 'http://127.0.0.1:8785/api/async-orders/metrics' -TimeoutSec 3
        return $web.StatusCode -eq 200 -and $order.status -eq 'UP' -and
            $inventory.status -eq 'UP' -and $payment.status -eq 'UP' -and $kafka.broker -eq 'UP'
    } catch {
        return $false
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker was not found. Install and start Docker Desktop first.'
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Engine is not running. Start Docker Desktop and wait until it is ready.'
}

$running = @(docker compose ps --status running --services 2>$null)
if (($requiredServices | Where-Object { $_ -notin $running }).Count -eq 0 -and (Test-BillHealth)) {
    Write-Host 'Bill System is already running: http://127.0.0.1:3100/' -ForegroundColor Green
    Start-Process 'http://127.0.0.1:3100/'
    exit 0
}

if ($running.Count -eq 0) {
    foreach ($port in $requiredPorts) {
        $listeners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
        if ($listeners) {
            $pids = ($listeners | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
            throw "Port $port is already in use by another program (PID $pids). Startup stopped safely."
        }
    }
} else {
    Write-Host 'A partial Bill System stack was found. Recovering missing or unhealthy services.' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '  Bill System - Transaction Reliability Lab' -ForegroundColor Cyan
Write-Host '  Building and starting MySQL, Redis, Kafka, three services, and the React console.' -ForegroundColor DarkGray
Write-Host ''

docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose build or startup failed.' }

$deadline = (Get-Date).AddMinutes(6)
do {
    if (Test-BillHealth) {
        Write-Host ''
        Write-Host 'Bill System is ready: http://127.0.0.1:3100/' -ForegroundColor Green
        Write-Host 'Order API: http://127.0.0.1:8785/' -ForegroundColor DarkGray
        Start-Process 'http://127.0.0.1:3100/'
        exit 0
    }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)

docker compose ps
throw 'Services did not become ready within six minutes. Run docker compose logs for details.'
