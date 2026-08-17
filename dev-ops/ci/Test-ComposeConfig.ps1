$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '../..')
$devOps = Join-Path $repoRoot 'dev-ops'

$cases = @(
  @{ Name = 'environment'; Files = @('docker-compose-environment.yml') },
  @{ Name = 'app'; Files = @('docker-compose-app.yml') },
  @{ Name = 'elk'; Files = @('docker-compose-environment.yml', 'docker-compose-elk.yml') },
  @{ Name = 'grafana'; Files = @('docker-compose-environment.yml', 'docker-compose-grafana.yml') },
  @{ Name = 'minio-tunnel'; Files = @('docker-compose-minio-tunnel.yml') },
  @{
    Name = 'full local stack'
    Files = @(
      'docker-compose-environment.yml',
      'docker-compose-elk.yml',
      'docker-compose-grafana.yml',
      'docker-compose-app.yml'
    )
  }
)

foreach ($case in $cases) {
  $arguments = @('compose', '--project-directory', $devOps)
  foreach ($file in $case.Files) {
    $arguments += @('-f', (Join-Path $devOps $file))
  }
  $arguments += @('config', '--quiet')
  & docker @arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Compose validation failed: $($case.Name)"
  }
  Write-Host "Validated Compose: $($case.Name)"
}

$environment = (& docker compose --project-directory $devOps `
    -f (Join-Path $devOps 'docker-compose-environment.yml') `
    config --format json) | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) {
  throw 'Could not inspect environment Compose configuration'
}

if ($null -ne $environment.services.neo4j.profiles -and
    @($environment.services.neo4j.profiles).Count -gt 0) {
  throw 'Neo4j must start by default; do not add a Compose profile'
}

if ($null -ne $environment.services.PSObject.Properties['createbuckets']) {
  throw 'Do not add a one-shot createbuckets service; the backend creates the MinIO bucket on demand'
}

if ($null -ne $environment.services.neo4j.environment.NEO4J_PASSWORD) {
  throw 'Do not pass NEO4J_PASSWORD to the Neo4j container; use NEO4J_AUTH'
}

$requiredServices = @('mysql', 'redis', 'elasticsearch', 'minio', 'rabbitmq', 'neo4j')
foreach ($serviceName in $requiredServices) {
  if ($null -eq $environment.services.PSObject.Properties[$serviceName]) {
    throw "Environment service is missing: $serviceName"
  }
}

Write-Host 'Validated default environment services: MySQL, Redis, Elasticsearch, MinIO, RabbitMQ, Neo4j'

$app = (& docker compose --project-directory $devOps `
    -f (Join-Path $devOps 'docker-compose-app.yml') `
    config --format json) | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) {
  throw 'Could not inspect app Compose configuration'
}

function Get-ComposeMemoryLimit {
  param($Service)
  if ($null -eq $Service) {
    return $null
  }
  if ($null -ne $Service.mem_limit -and "$($Service.mem_limit)" -ne '') {
    return $Service.mem_limit
  }
  $deployMemory = $Service.deploy.resources.limits.memory
  if ($null -ne $deployMemory -and "$deployMemory" -ne '') {
    return $deployMemory
  }
  return $null
}

$cappedServices = @(
  'mysql',
  'redis',
  'elasticsearch',
  'minio',
  'rabbitmq',
  'neo4j',
  'xxl-job-mysql',
  'xxl-job-admin',
  'app',
  'frontend'
)
foreach ($serviceName in $cappedServices) {
  $service = $app.services.PSObject.Properties[$serviceName].Value
  if ($null -eq $service) {
    throw "App service is missing: $serviceName"
  }
  if ($null -eq (Get-ComposeMemoryLimit $service)) {
    throw "App service $serviceName is missing mem_limit (4C6G / 上线需要内存盖)"
  }
}

$neo4jEnv = $app.services.neo4j.environment
$heapMax = $neo4jEnv.NEO4J_server_memory_heap_max__size
if ([string]::IsNullOrWhiteSpace("$heapMax")) {
  throw 'Neo4j must pin server.memory.heap.max_size; do not let 5.x size itself against host RAM'
}

$appJavaOpts = $app.services.app.environment.JAVA_OPTS
if ("$appJavaOpts" -notmatch 'Xmx') {
  throw 'App JAVA_OPTS must set -Xmx so MaxRAMPercentage cannot claim the whole host'
}

Write-Host 'Validated app memory caps: ES, Neo4j, XXL-Job, backend, and remaining 4C6G stack'
