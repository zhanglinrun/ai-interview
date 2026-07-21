$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '../..')
$devOps = Join-Path $repoRoot 'dev-ops'

# CI-only placeholders. They are deliberately non-secret and never written to a file.
$env:APP_JWT_SECRET = 'ci-placeholder-jwt-secret-at-least-32-bytes'
$env:APP_AI_CONFIG_ENCRYPTION_KEY = 'ci-placeholder-encryption-key-32-bytes'
$env:AI_BAILIAN_API_KEY = 'ci-placeholder-bailian-key'
$env:MINERU_API_TOKEN = 'ci-placeholder-mineru-token'
$env:MYSQL_ROOT_PASSWORD = 'ci-placeholder-mysql-root'
$env:MYSQL_PASSWORD = 'ci-placeholder-mysql-user'
$env:MINIO_ACCESS_KEY = 'ci-placeholder-minio-user'
$env:MINIO_SECRET_KEY = 'ci-placeholder-minio-password'
$env:MINIO_EXTERNAL_ENDPOINT = 'https://files.interview.example.com'
$env:RABBITMQ_PASSWORD = 'ci-placeholder-rabbitmq'
$env:APP_DOMAIN = 'interview.example.com'
$env:FILES_DOMAIN = 'files.interview.example.com'
$env:ACME_EMAIL = 'ops@example.com'
$env:CORS_ALLOWED_ORIGINS = 'https://interview.example.com'
$env:GRAFANA_ADMIN_PASSWORD = 'ci-placeholder-grafana'
$env:KIBANA_ENCRYPTION_KEY = 'ci-placeholder-kibana-key-32-bytes'

$cases = @(
  @{ Name = 'development dependencies'; Files = @('docker-compose-environment.yml') },
  @{ Name = 'development full stack'; Files = @('docker-compose-app.yml') },
  @{
    Name = 'development monitoring overlay'
    Files = @('docker-compose-environment.yml', 'docker-compose-monitor.yml')
  },
  @{ Name = 'production IP'; Files = @('docker-compose-ip.yml') },
  @{ Name = 'production HTTPS'; Files = @('docker-compose-prod.yml') },
  @{
    Name = 'production observability'
    Files = @('docker-compose-prod.yml', 'docker-compose-observability.yml')
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

# MySQL 官方镜像首次初始化会启动一个只开放 Unix Socket 的临时 server。
# 若健康检查走 localhost/socket，Compose 会过早启动 app，随后临时 server 关闭时连接失败。
$mysqlHealthFiles = @(
  'docker-compose-environment.yml',
  'docker-compose-app.yml',
  'docker-compose-ip.yml',
  'docker-compose-prod.yml'
)
foreach ($file in $mysqlHealthFiles) {
  $document = (& docker compose --project-directory $devOps `
      -f (Join-Path $devOps $file) config --format json) | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect MySQL healthcheck: $file"
  }
  $healthCommand = @($document.services.mysql.healthcheck.test) -join ' '
  if ($healthCommand -notmatch '--protocol=TCP' -or
      $healthCommand -notmatch '127\.0\.0\.1') {
    throw "MySQL healthcheck must wait for the final TCP server: $file"
  }
  Write-Host "Validated MySQL TCP readiness: $file"
}

foreach ($file in @('docker-compose-ip.yml', 'docker-compose-prod.yml')) {
  $document = (& docker compose --project-directory $devOps `
      -f (Join-Path $devOps $file) config --format json) | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect RabbitMQ configuration: $file"
  }
  $rabbitEnvironment = @($document.services.rabbitmq.environment.PSObject.Properties.Name)
  if ('RABBITMQ_VM_MEMORY_HIGH_WATERMARK' -in $rabbitEnvironment) {
    throw "RabbitMQ 3.13 rejects deprecated memory watermark environment variable: $file"
  }
  $frontendHealth = @($document.services.frontend.healthcheck.test) -join ' '
  if ($frontendHealth -notmatch '127\.0\.0\.1/healthz') {
    throw "Frontend healthcheck must use the nginx IPv4 listener: $file"
  }
  if ($document.services.app.environment.SPRINGDOC_API_DOCS_ENABLED -ne 'false' -or
      $document.services.app.environment.SPRINGDOC_SWAGGER_UI_ENABLED -ne 'false') {
    throw "Production entrypoint must disable SpringDoc endpoints: $file"
  }
  Write-Host "Validated RabbitMQ 3.13 environment: $file"
  Write-Host "Validated frontend IPv4 readiness: $file"
  Write-Host "Validated production SpringDoc boundary: $file"
}

& docker compose --project-directory $devOps --profile logs `
  -f (Join-Path $devOps 'docker-compose-prod.yml') `
  -f (Join-Path $devOps 'docker-compose-observability.yml') config --quiet
if ($LASTEXITCODE -ne 0) {
  throw 'Compose validation failed: production HTTPS with logs profile'
}
Write-Host 'Validated Compose: production HTTPS with logs profile'

$productionFiles = @(
  (Join-Path $devOps 'docker-compose-prod.yml'),
  (Join-Path $devOps 'docker-compose-observability.yml')
)
$arguments = @('compose', '--project-directory', $devOps, '--profile', 'logs')
foreach ($file in $productionFiles) {
  $arguments += @('-f', $file)
}
$arguments += @('config', '--format', 'json')
$production = (& docker @arguments) | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) {
  throw 'Could not inspect merged production Compose policy'
}

$coreServices = @(
  'mysql', 'redis', 'elasticsearch', 'minio', 'rabbitmq', 'app', 'frontend', 'caddy'
)
$observabilityServices = @('prometheus', 'grafana', 'logstash', 'kibana')
$longLivedServices = $coreServices + $observabilityServices
foreach ($serviceName in $longLivedServices) {
  $service = $production.services.$serviceName
  if ($null -eq $service) {
    throw "Production service is missing: $serviceName"
  }
  if ([long]$service.mem_limit -le 0 -or [double]$service.cpus -le 0) {
    throw "Production service has no memory/CPU limit: $serviceName"
  }
  if ($service.restart -ne 'unless-stopped') {
    throw "Production service has no unless-stopped restart policy: $serviceName"
  }
  if ($null -eq $service.healthcheck) {
    throw "Production service has no healthcheck: $serviceName"
  }
  $loggingInvalid = $service.logging.driver -ne 'json-file' -or
    $service.logging.options.'max-size' -ne '10m' -or
    $service.logging.options.'max-file' -ne '3'
  if ($loggingInvalid) {
    throw "Production service has no bounded json-file logging policy: $serviceName"
  }
}

$coreMemory = ($coreServices | ForEach-Object {
    [long]$production.services.$_.mem_limit
  } | Measure-Object -Sum).Sum
$fullMemory = ($longLivedServices | ForEach-Object {
    [long]$production.services.$_.mem_limit
  } | Measure-Object -Sum).Sum
$fiveGiB = 5L * 1GB
if ($fullMemory -gt $fiveGiB) {
  throw "Long-lived production memory limits exceed 5 GiB: $fullMemory bytes"
}

$privateInfrastructure = @('mysql', 'redis', 'elasticsearch', 'minio', 'rabbitmq', 'app', 'frontend')
foreach ($serviceName in $privateInfrastructure) {
  $service = $production.services.PSObject.Properties[$serviceName].Value
  $portProperty = $service.PSObject.Properties['ports']
  if ($null -ne $portProperty -and @($portProperty.Value).Count -gt 0) {
    throw "Production service must not publish a host port: $serviceName"
  }
}
$caddyPorts = @($production.services.caddy.ports | ForEach-Object target | Sort-Object)
if (($caddyPorts -join ',') -ne '80,443') {
  throw "Caddy must be the only 80/443 edge: $($caddyPorts -join ',')"
}
foreach ($serviceName in @('prometheus', 'grafana', 'kibana')) {
  $service = $production.services.PSObject.Properties[$serviceName].Value
  $ports = @($service.ports)
  if ($ports.Count -ne 1 -or $ports[0].host_ip -ne '127.0.0.1') {
    throw "Observability port must bind only to 127.0.0.1: $serviceName"
  }
}

$coreMiB = [Math]::Round($coreMemory / 1MB)
$fullMiB = [Math]::Round($fullMemory / 1MB)
Write-Host "Validated 4C6G policy: core=$coreMiB MiB, full-observability=$fullMiB MiB"
