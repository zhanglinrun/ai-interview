param(
  [switch]$FullStack,
  [switch]$SkipPackage
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$DevOps = Join-Path $Root "dev-ops"
$EnvFile = Join-Path $Root ".env"

if (Test-Path -LiteralPath $EnvFile) {
  foreach ($RawLine in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
    $Line = $RawLine.Trim()
    if ([string]::IsNullOrWhiteSpace($Line) -or $Line.StartsWith("#")) {
      continue
    }
    $Index = $Line.IndexOf("=")
    if ($Index -le 0) {
      continue
    }
    $Name = $Line.Substring(0, $Index).Trim()
    $Value = $Line.Substring($Index + 1).Trim()
    if (($Value.StartsWith('"') -and $Value.EndsWith('"')) -or ($Value.StartsWith("'") -and $Value.EndsWith("'"))) {
      $Value = $Value.Substring(1, $Value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
  }
}

Push-Location $DevOps

if ($FullStack) {
  if (-not $SkipPackage) {
    Push-Location (Join-Path $Root "backend")
    mvn clean package -DskipTests
    Pop-Location
  }
  docker compose -f docker-compose-app.yml up -d --build
} else {
  docker compose -f docker-compose-environment.yml up -d
}
Pop-Location

Write-Host "ai-interview dev dependencies started"
if ($FullStack) {
  Write-Host "frontend: http://localhost:28080"
  Write-Host "backend:  http://localhost:28082"
} else {
  Write-Host "backend (local): mvn spring-boot:run -> http://localhost:8082"
  Write-Host "frontend (local): pnpm dev -> http://localhost:5174"
}
