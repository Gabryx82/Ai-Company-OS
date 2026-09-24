<#
.SYNOPSIS
  Creates the operator's local secrets file, once, outside the repository (ADR-024 §6).

.DESCRIPTION
  Writes %USERPROFILE%\.aicos\local.env (or $env:AICOS_HOME\local.env) with:
    AICOS_ADMIN_USERNAME  the first admin's username (admin)
    AICOS_ADMIN_PASSWORD  a random 20-character password for that admin
    AICOS_ENGINE_TOKEN    a random token shared by the control plane and the AI Engine

  An existing file is never overwritten: run with -Show to print its admin credentials again.
  The control plane imports this file (application-dev.properties), the AI Engine reads it
  (app/config.py), and start-dev.ps1 loads it into the windows it opens. Environment variables
  set in the shell still win over it.

  The admin password is used only when the admin is created -- the first start against a
  database with no admin. Change it afterwards from the console (Impostazioni > Il mio account).
  To reset a forgotten one: set AICOS_ADMIN_PASSWORD to a new value in this file, start the
  control plane once with AICOS_ADMIN_RESET=true, then remove that variable.

.EXAMPLE
  .\scripts\init-local-secrets.ps1
  .\scripts\init-local-secrets.ps1 -Show
#>
param([switch]$Show)

$ErrorActionPreference = "Stop"
$home_ = if ($env:AICOS_HOME) { $env:AICOS_HOME } else { Join-Path $env:USERPROFILE ".aicos" }
$file = Join-Path $home_ "local.env"

function New-Secret([int]$length, [string]$alphabet) {
  $bytes = New-Object byte[] $length
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

if (Test-Path $file) {
  if ($Show) {
    Get-Content $file | Where-Object { $_ -match '^AICOS_ADMIN_(USERNAME|PASSWORD)=' }
  } else {
    Write-Host "   local secrets already present: $file"
  }
  return
}

New-Item -ItemType Directory -Force $home_ | Out-Null
# No ambiguous characters (0/O, 1/l/I): the password may be read off a screen and typed.
$letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
$password = (New-Secret 20 $letters) -replace '(.{5})(?!$)', '$1-'
$engine = New-Secret 40 $letters
@(
  "# AI Company OS -- local secrets. Never commit this file. Created $(Get-Date -Format s).",
  "AICOS_ADMIN_USERNAME=admin",
  "AICOS_ADMIN_PASSWORD=$password",
  "AICOS_ENGINE_TOKEN=$engine"
) | Set-Content -Path $file -Encoding ascii

# Readable by this Windows account only.
& icacls $file /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null

Write-Host "   created $file"
Write-Host "   admin username: admin"
Write-Host "   admin password: $password   (change it after the first sign-in)"
