<#
.SYNOPSIS
  Rolls the local model upgrade of PHASE 29 (ADR-034) back, or forward again. Dry run by default.

.DESCRIPTION
  Three independent parts; each can be skipped.

  1. Ollama. Checks that the models the upgrade replaced are still installed (deepseek-coder-v2:16b,
     llama3.2:3b, gemma4:e4b-legacy) and pulls the ones that can be pulled. With -RestoreGemmaPackage it
     also puts the previous gemma4:e4b package back under its tag, keeping the new one as gemma4:e4b-p29.

  2. 3D Omniverse (no git there). Copies back the files saved before the upgrade from
     <OmniversePath>\backup\pre-model-upgrade-2026-09-30 -- configuration (models, agents, LiteLLM
     aliases), backend, frontend label, tests. The files replaced now are saved first in
     <OmniversePath>\backup\post-model-upgrade-<timestamp>, so -Direction Forward can undo the rollback.

  3. AI Company OS data. V27 and seed V3 moved agents and catalog rows; Flyway does not undo data. This
     part moves back only the rows still exactly as PHASE 29 left them: an agent or catalog entry the
     operator changed afterwards stays as it is. -Direction Forward repeats V27 and seed V3.

  The code itself is rolled back with git: the upgrade lives on autonomous/phase-29-local-model-upgrade,
  the branch before it is autonomous/phase-28-project-blueprint. The engine's fallbacks and context
  window then disappear with the code; without switching code they can be switched off in the
  environment: AICOS_ENGINE_OLLAMA_FALLBACKS= (empty) and AICOS_ENGINE_OLLAMA_NUM_CTX=0.

.EXAMPLE
  .\scripts\rollback-local-models.ps1                                # dry run: what would change
  .\scripts\rollback-local-models.ps1 -Apply -Database aicompany_try  # on a clone first
  .\scripts\rollback-local-models.ps1 -Apply -Direction Forward -Database aicompany_try
#>
param(
  [ValidateSet("Back", "Forward")] [string]$Direction = "Back",
  [switch]$Apply,
  [string]$Database = "aicompany",
  [string]$OmniversePath = (Join-Path $env:USERPROFILE "3D Omniverse"),
  [switch]$SkipOllama,
  [switch]$SkipOmniverse,
  [switch]$SkipDatabase,
  [switch]$RestoreGemmaPackage
)

$ErrorActionPreference = "Stop"
$mode = if ($Apply) { "APPLY" } else { "DRY RUN" }
Write-Host "Local model upgrade (PHASE 29) -- direction $Direction -- $mode" -ForegroundColor Cyan

# --- 1. Ollama ---------------------------------------------------------------------------------
if (-not $SkipOllama) {
  Write-Host "`n[1] Ollama" -ForegroundColor Yellow
  $installed = (ollama list) | Select-Object -Skip 1 | ForEach-Object { ($_ -split '\s+')[0] }
  $previous = @("deepseek-coder-v2:16b", "llama3.2:3b", "qwen3.5:4b", "qwen3.5:9b", "gemma4:e4b")
  foreach ($model in $previous) {
    if ($installed -contains $model) { Write-Host "  ok       $model" }
    elseif ($Apply) { Write-Host "  pulling  $model"; ollama pull $model | Out-Null }
    else { Write-Host "  missing  $model (would pull)" }
  }
  if ($installed -notcontains "gemma4:e4b-legacy") {
    Write-Host "  missing  gemma4:e4b-legacy: the previous gemma package is gone; gemma4:e4b is the new one" -ForegroundColor DarkYellow
  } elseif ($RestoreGemmaPackage -and $Direction -eq "Back") {
    Write-Host "  gemma4:e4b <- gemma4:e4b-legacy (the new package stays as gemma4:e4b-p29)"
    if ($Apply) { ollama cp gemma4:e4b gemma4:e4b-p29 | Out-Null; ollama cp gemma4:e4b-legacy gemma4:e4b | Out-Null }
  } elseif ($RestoreGemmaPackage -and $Direction -eq "Forward" -and $installed -contains "gemma4:e4b-p29") {
    Write-Host "  gemma4:e4b <- gemma4:e4b-p29"
    if ($Apply) { ollama cp gemma4:e4b-p29 gemma4:e4b | Out-Null }
  }
}

# --- 2. 3D Omniverse ---------------------------------------------------------------------------
if (-not $SkipOmniverse) {
  Write-Host "`n[2] 3D Omniverse: $OmniversePath" -ForegroundColor Yellow
  $before = Join-Path $OmniversePath "backup\pre-model-upgrade-2026-09-30"
  $files = @("config\models.json", "config\agents.json", "config\litellm_config.yaml", "backend\orchestrator.py",
             "backend\agents.py", "backend\settings.py", "frontend\app.js", "tests\test_ecosystem.py", "start.ps1")
  if ($Direction -eq "Back") {
    $source = $before
  } else {
    $source = Get-ChildItem (Join-Path $OmniversePath "backup") -Directory -Filter "post-model-upgrade-*" |
      Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName
    if (-not $source) { throw "No post-model-upgrade backup to go forward from: run the rollback first." }
  }
  $saveTo = Join-Path $OmniversePath ("backup\post-model-upgrade-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
  foreach ($file in $files) {
    $from = Join-Path $source $file
    $to = Join-Path $OmniversePath $file
    if (-not (Test-Path $from)) { continue }
    if ((Test-Path $to) -and ((Get-FileHash $from).Hash -eq (Get-FileHash $to).Hash)) { Write-Host "  same     $file"; continue }
    Write-Host "  restore  $file"
    if ($Apply) {
      if ($Direction -eq "Back") {
        New-Item -ItemType Directory -Force (Split-Path (Join-Path $saveTo $file)) | Out-Null
        Copy-Item $to (Join-Path $saveTo $file)
      }
      Copy-Item $from $to -Force
    }
  }
}

# --- 3. AI Company OS data ---------------------------------------------------------------------
if (-not $SkipDatabase) {
  Write-Host "`n[3] AI Company OS database: $Database" -ForegroundColor Yellow
  $back = @"
BEGIN;
UPDATE agents SET model = 'ollama:deepseek-coder-v2:16b',
       baseline = replace(baseline, '"model":"ollama:qwen3.5:9b"', '"model":"ollama:deepseek-coder-v2:16b"'),
       version = version + 1, updated_at = now()
 WHERE origin = 'SEED' AND name IN ('Frontend Developer', 'Database Specialist')
   AND model = 'ollama:qwen3.5:9b' AND baseline LIKE '%"model":"ollama:qwen3.5:9b"%';
UPDATE agents SET model = 'ollama:qwen3.5:4b',
       baseline = replace(baseline, '"model":"ollama:lfm2.5:8b"', '"model":"ollama:qwen3.5:4b"'),
       version = version + 1, updated_at = now()
 WHERE origin = 'TEMPLATE' AND name = 'Test Engineer' AND role = 'Test Engineer'
   AND model = 'ollama:lfm2.5:8b' AND baseline LIKE '%"model":"ollama:lfm2.5:8b"%';
UPDATE llm_models SET lifecycle = 'ACTIVE', replaced_by = NULL, version = version + 1, updated_at = now()
 WHERE key = 'ollama:deepseek-coder-v2:16b' AND lifecycle = 'DEPRECATED' AND replaced_by = 'ollama:qwen3.5:9b';
UPDATE llm_models SET size_gb = 9.6, version = version + 1, updated_at = now()
 WHERE key = 'ollama:gemma4:e4b' AND size_gb = 6.6;
SELECT name, model FROM agents WHERE name IN ('Frontend Developer', 'Database Specialist', 'Test Engineer') ORDER BY name;
SELECT key, lifecycle, replaced_by, size_gb FROM llm_models WHERE key IN ('ollama:deepseek-coder-v2:16b', 'ollama:gemma4:e4b');
COMMIT;
"@
  $forward = @"
BEGIN;
UPDATE agents SET model = 'ollama:qwen3.5:9b',
       baseline = replace(baseline, '"model":"ollama:deepseek-coder-v2:16b"', '"model":"ollama:qwen3.5:9b"'),
       version = version + 1, updated_at = now()
 WHERE origin = 'SEED' AND name IN ('Frontend Developer', 'Database Specialist')
   AND model = 'ollama:deepseek-coder-v2:16b' AND baseline LIKE '%"model":"ollama:deepseek-coder-v2:16b"%';
UPDATE agents SET model = 'ollama:lfm2.5:8b',
       baseline = replace(baseline, '"model":"ollama:qwen3.5:4b"', '"model":"ollama:lfm2.5:8b"'),
       version = version + 1, updated_at = now()
 WHERE origin = 'TEMPLATE' AND name = 'Test Engineer' AND role = 'Test Engineer'
   AND model = 'ollama:qwen3.5:4b' AND baseline LIKE '%"model":"ollama:qwen3.5:4b"%';
UPDATE llm_models SET lifecycle = 'DEPRECATED', replaced_by = 'ollama:qwen3.5:9b', version = version + 1, updated_at = now()
 WHERE key = 'ollama:deepseek-coder-v2:16b' AND lifecycle = 'ACTIVE' AND replaced_by IS NULL;
UPDATE llm_models SET size_gb = 6.6, version = version + 1, updated_at = now()
 WHERE key = 'ollama:gemma4:e4b' AND size_gb = 9.6;
SELECT name, model FROM agents WHERE name IN ('Frontend Developer', 'Database Specialist', 'Test Engineer') ORDER BY name;
SELECT key, lifecycle, replaced_by, size_gb FROM llm_models WHERE key IN ('ollama:deepseek-coder-v2:16b', 'ollama:gemma4:e4b');
COMMIT;
"@
  $sql = if ($Direction -eq "Back") { $back } else { $forward }
  if ($Apply) {
    $sql | docker exec -i aicompany-postgres psql -v ON_ERROR_STOP=1 -U aicompany -d $Database
    if ($LASTEXITCODE -ne 0) { throw "psql failed ($LASTEXITCODE): nothing was committed" }
  } else {
    Write-Host "  would run on ${Database}:" ; Write-Host $sql -ForegroundColor DarkGray
  }
}

Write-Host "`nDone ($mode)." -ForegroundColor Cyan
