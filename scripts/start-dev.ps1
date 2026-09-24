<#
.SYNOPSIS
  Starts the whole AI Company OS stack for local development, each service in its own window.

.DESCRIPTION
  1. PostgreSQL (docker compose), and waits until it accepts connections.
  2. The AI Engine (ai-engine, Python), creating its virtualenv on first use.   -> http://127.0.0.1:8090
  3. The control plane (backend, Spring Boot, profile dev).                    -> http://localhost:8081
  4. The operator console (frontend, Vite).                                    -> http://localhost:5173

  A service whose port is already listening is not started again, so the script can be re-run after
  one window was closed or failed.

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

# The Maven wrapper (mvnw.cmd) calls "powershell" by name. On a machine whose PATH does not contain
# the Windows PowerShell directory it fails at once with "Cannot start maven from wrapper" -- found
# on the first real run of this script (2026-09-23). The windows below are started with the full
# path of this PowerShell, and get its directory prepended to their own PATH: the process only,
# never the system setting.
$powershell = Join-Path $PSHOME "powershell.exe"
if (-not (Test-Path $powershell)) { $powershell = (Get-Process -Id $PID).Path }
$pathFix = "`$env:Path = '$PSHOME;' + `$env:Path"

function Test-Listening([int]$port) {
  return [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

function Start-Window([string]$title, [string]$directory, [string]$command) {
  Start-Process $powershell -ArgumentList "-NoExit", "-Command",
    "$pathFix; Set-Location '$directory'; `$host.UI.RawUI.WindowTitle = '$title'; $command"
}

Write-Host "== PostgreSQL" -ForegroundColor Cyan
# docker writes its progress to stderr. Under Windows PowerShell 5.1 with ErrorActionPreference=Stop
# that becomes a terminating error whenever the output is redirected, so native commands are judged
# by their exit code, never by stderr.
Push-Location $root
$ErrorActionPreference = "Continue"
docker compose up -d 2>&1 | ForEach-Object { "   $_" } | Out-Host
$composeExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
Pop-Location
if ($composeExit -ne 0) { throw "docker compose up failed (exit $composeExit); is Docker Desktop running?" }
$ready = $false
$ErrorActionPreference = "Continue"
for ($i = 0; $i -lt 30; $i++) {
  docker exec aicompany-postgres pg_isready -U aicompany -d aicompany *> $null
  if ($LASTEXITCODE -eq 0) { $ready = $true; break }
  Start-Sleep -Seconds 1
}
$ErrorActionPreference = "Stop"
if (-not $ready) { throw "PostgreSQL did not become ready; see 'docker compose logs postgres'" }

Write-Host "== AI Engine" -ForegroundColor Cyan
if (Test-Listening 8090) {
  Write-Host "   already listening on 8090, not started again"
} else {
  $engine = Join-Path $root "ai-engine"
  $python = Join-Path $engine ".venv\Scripts\python.exe"
  if (-not (Test-Path $python)) {
    Write-Host "   creating the virtualenv (first run only)"
    Push-Location $engine
    python -m venv .venv
    & $python -m pip install -q -r requirements.txt
    Pop-Location
  }
  Start-Window "AICOS AI Engine :8090" $engine "& '$python' -m app"
}

Write-Host "== Control plane (database: $Database)" -ForegroundColor Cyan
if (Test-Listening 8081) {
  Write-Host "   already listening on 8081, not started again (its database is whatever it was started with)"
} else {
  $backend = Join-Path $root "backend"
  Start-Window "AICOS control plane :8081" $backend `
    "`$env:POSTGRES_DB = '$Database'; .\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false'"
}

if (-not $SkipFrontend) {
  Write-Host "== Operator console" -ForegroundColor Cyan
  if (Test-Listening 5173) {
    Write-Host "   already listening on 5173, not started again"
  } else {
    $frontend = Join-Path $root "frontend"
    if (-not (Test-Path (Join-Path $frontend "node_modules"))) {
      Push-Location $frontend
      npm ci
      Pop-Location
    }
    Start-Window "AICOS console :5173" $frontend "npm run dev"
  }
}

Write-Host "== Waiting for the control plane (first start compiles and migrates: up to 3 minutes)" -ForegroundColor Cyan
$up = $false
for ($i = 0; $i -lt 180; $i++) {
  try {
    # 127.0.0.1, not localhost: the backend listens on IPv4 loopback only, and "localhost" costs a
    # refused IPv6 attempt (about 2 s) on every poll.
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8081/actuator/health" -TimeoutSec 2
    if ($health.status -eq "UP") { $up = $true; break }
  } catch { Start-Sleep -Seconds 1 }
}

Write-Host ""
if (-not $up) {
  # Saying the stack is up when the backend is not is how a console ends up reporting
  # "control plane unreachable" with no clue why. Say it here, where the cause is.
  Write-Host "The control plane did not come up. Read the window titled 'AICOS control plane :8081'." -ForegroundColor Red
  exit 1
}
Write-Host "Console:        http://localhost:5173" -ForegroundColor Green
Write-Host "Control plane:  http://localhost:8081   (token: `$env:AICOS_OPERATOR_TOKEN or dev-operator-token-change-me)"
Write-Host "AI Engine:      http://127.0.0.1:8090   (local models through Ollama if it is running)"
