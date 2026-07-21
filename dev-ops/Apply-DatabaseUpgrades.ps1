$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $repoRoot '.env'
$upgradeDirectory = Join-Path $repoRoot 'backend/src/main/resources/sql/upgrade'

if (-not (Test-Path -LiteralPath $envFile)) {
  throw "Missing environment file: $envFile"
}
if (-not (Test-Path -LiteralPath $upgradeDirectory)) {
  throw "Missing database upgrade directory: $upgradeDirectory"
}

$envMap = @{}
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
  if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
    $envMap[$matches[1]] = $matches[2]
  }
}

$rootPassword = $envMap['MYSQL_ROOT_PASSWORD']
$databaseName = $envMap['MYSQL_DB']
$containerName = if ([string]::IsNullOrWhiteSpace($envMap['MYSQL_CONTAINER_NAME'])) {
  'interview-mysql'
} else {
  $envMap['MYSQL_CONTAINER_NAME']
}

if ([string]::IsNullOrWhiteSpace($rootPassword)) {
  throw 'MYSQL_ROOT_PASSWORD is missing from .env'
}
if ([string]::IsNullOrWhiteSpace($databaseName)) {
  throw 'MYSQL_DB is missing from .env'
}

$running = & docker inspect --format '{{.State.Running}}' $containerName 2>$null
if ($LASTEXITCODE -ne 0 -or $running.Trim() -ne 'true') {
  throw "MySQL container is not running: $containerName"
}

$upgradeFiles = @(Get-ChildItem -LiteralPath $upgradeDirectory -File -Filter '*.sql' |
    Sort-Object Name)
if ($upgradeFiles.Count -eq 0) {
  Write-Host 'No database upgrade scripts found'
  exit 0
}

$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
foreach ($upgradeFile in $upgradeFiles) {
  Write-Host "Applying database upgrade: $($upgradeFile.Name)"
  $sql = Get-Content -LiteralPath $upgradeFile.FullName -Raw -Encoding UTF8
  $sql | & docker exec -i `
    -e "MYSQL_PWD=$rootPassword" `
    $containerName `
    mysql `
    --default-character-set=utf8mb4 `
    --user=root `
    "--database=$databaseName"
  if ($LASTEXITCODE -ne 0) {
    throw "Database upgrade failed: $($upgradeFile.Name)"
  }
}

Write-Host "Applied $($upgradeFiles.Count) database upgrade script(s)"
