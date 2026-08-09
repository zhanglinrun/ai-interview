$ErrorActionPreference = "Stop"

$DevOps = $PSScriptRoot

Push-Location $DevOps
docker compose -f docker-compose-app.yml down
docker compose -f docker-compose-elk.yml down
docker compose -f docker-compose-grafana.yml down
docker compose -f docker-compose-environment.yml down
Pop-Location

Write-Host "ai-interview stopped"
