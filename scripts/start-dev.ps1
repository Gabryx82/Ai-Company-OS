<#
.SYNOPSIS
  Starts the whole AI Company OS stack for local development, each service in its own window.

.DESCRIPTION
  1. PostgreSQL (docker compose), and waits until it accepts connections.
  2. The AI Engine (ai-engine, Python), creating its virtualenv on first use.   -> http://127.0.0.1:8090
  3. The control plane (backend, Spring Boot, profile dev).                    -> http://localhost:8080
  4. The operator console (frontend, Vite).                                    -> http://localhost:5173

  Sign in to the console with the operator token: AICOS_OPERATOR_TOKEN if set, otherwise the local
  default "dev-operator-token-change-me". The backend and the engine share the dev default engine
  token unless AICOS_ENGINE_TOKEN is set -- set it in this shell and both windows inherit it.

  -Database chooses the PostgreSQL database the backend uses (default: aicompany). On a database
  behind the head of the migration stream, the backend applies the missing migrations at start --
  including V8, the one destructive migration, authorized on 2026-09-19 (ADR-012). To try the stack
  without touching your data, clone it first:
    docker exec aicompany-postgres psql -U aicompany -d postgres -c "CREATE DATABASE aicompany_try TEMPLATE aicompany;"
    .\scripts\start-dev.ps1 -Database aicompany_try

.EXAMPLE
  .\scripts\start-dev.ps1
#>
param(
  [string]$Database = "aicompany",
  [switch]$SkipFrontend
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Write-Host "== PostgreSQL" -ForegroundColor Cyan
Push-Location $root
docker compose up -d | Out-Host
Pop-Location
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
  docker exec aicompany-postgres pg_isready -U aicompany -d aicompany *> $null
  if ($LASTEXITCODE -eq 0) { $ready = $true; break }
  Start-Sleep -Seconds 1
}
if (-not $ready) { throw "PostgreSQL did not become ready; see 'docker compose logs postgres'" }

Write-Host "== AI Engine" -ForegroundColor Cyan
$engine = Join-Path $root "ai-engine"
$python = Join-Path $engine ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
  Write-Host "   creating the virtualenv (first run only)"
  Push-Location $engine
  python -m venv .venv
  & $python -m pip install -q -r requirements.txt
  Pop-Location
}
Start-Process powershell -ArgumentList "-NoExit", "-Command",
  "Set-Location '$engine'; `$host.UI.RawUI.WindowTitle = 'AICOS AI Engine :8090'; & '$python' -m app"

Write-Host "== Control plane (database: $Database)" -ForegroundColor Cyan
$backend = Join-Path $root "backend"
Start-Process powershell -ArgumentList "-NoExit", "-Command",
  "Set-Location '$backend'; `$host.UI.RawUI.WindowTitle = 'AICOS control plane :8080'; `$env:POSTGRES_DB = '$Database'; .\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false'"

if (-not $SkipFrontend) {
  Write-Host "== Operator console" -ForegroundColor Cyan
  $frontend = Join-Path $root "frontend"
  if (-not (Test-Path (Join-Path $frontend "node_modules"))) {
    Push-Location $frontend
    npm ci
    Pop-Location
  }
  Start-Process powershell -ArgumentList "-NoExit", "-Command",
    "Set-Location '$frontend'; `$host.UI.RawUI.WindowTitle = 'AICOS console :5173'; npm run dev"
}

Write-Host "== Waiting for the control plane" -ForegroundColor Cyan
for ($i = 0; $i -lt 120; $i++) {
  try {
    $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 2
    if ($health.status -eq "UP") { break }
  } catch { Start-Sleep -Seconds 1 }
}

Write-Host ""
Write-Host "Console:        http://localhost:5173" -ForegroundColor Green
Write-Host "Control plane:  http://localhost:8080   (token: `$env:AICOS_OPERATOR_TOKEN or dev-operator-token-change-me)"
Write-Host "AI Engine:      http://127.0.0.1:8090   (local models through Ollama if it is running)"
