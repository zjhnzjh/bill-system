@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0tests\results" mkdir "%~dp0tests\results"
docker run --rm -i -e BASE_URL=http://host.docker.internal:8785 -v "%~dp0tests\k6:/scripts:ro" -v "%~dp0tests\results:/results" grafana/k6:0.54.0 run --summary-export=/results/k6-async-summary.json /scripts/async-order-load.js
echo.
echo HTTP 202 measures admission latency only. Open the Operations page and wait for Kafka Lag to drain to zero before judging completion.
pause
