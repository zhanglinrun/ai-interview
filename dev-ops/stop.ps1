$ErrorActionPreference = "Stop"

$DevOps = $PSScriptRoot

Push-Location $DevOps
docker compose -f docker-compose-app.yml down
docker compose -f docker-compose-monitor.yml down
docker compose -f docker-compose-environment.yml down
Pop-Location

Write-Host "ai-interview stopped"
