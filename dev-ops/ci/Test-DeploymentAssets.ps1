$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '../..')
$devOps = Join-Path $repoRoot 'dev-ops'

$upgradeRunner = Join-Path $devOps 'Apply-DatabaseUpgrades.ps1'
$vectorizationUpgrade = Join-Path $repoRoot `
  'backend/src/main/resources/sql/upgrade/2026-07-rag-vectorization-task.sql'
$evaluationMetricsUpgrade = Join-Path $repoRoot `
  'backend/src/main/resources/sql/upgrade/2026-07-rag-evaluation-metrics.sql'
foreach ($requiredFile in @($upgradeRunner, $vectorizationUpgrade, $evaluationMetricsUpgrade)) {
  if (-not (Test-Path -LiteralPath $requiredFile)) {
    throw "Missing database upgrade asset: $requiredFile"
  }
}
$upgradeSql = Get-Content -LiteralPath $vectorizationUpgrade -Raw -Encoding UTF8
foreach ($requiredToken in @(
    'embedding_attempt',
    'embedding_claimed_at',
    'embedding_next_retry_at',
    'embedding_terminal_failure',
    'idx_kbv_embedding_recovery'
  )) {
  if ($upgradeSql -notmatch [regex]::Escape($requiredToken)) {
    throw "Database upgrade is missing required token: $requiredToken"
  }
}
Write-Host 'Validated database upgrade assets'

$evaluationSql = Get-Content -LiteralPath $evaluationMetricsUpgrade -Raw -Encoding UTF8
foreach ($requiredToken in @('retrieval_recall', 'retrieval_precision')) {
  if ($evaluationSql -notmatch [regex]::Escape($requiredToken)) {
    throw "RAG evaluation upgrade is missing required token: $requiredToken"
  }
}

$dashboard = Join-Path $devOps 'monitor/grafana/dashboards/ai-interview-overview.json'
Get-Content -LiteralPath $dashboard -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null
Write-Host 'Validated Grafana dashboard JSON'

$caddyfile = (Resolve-Path -LiteralPath (Join-Path $devOps 'Caddyfile')).Path `
  -replace '\\', '/'
& docker run --rm `
  --env APP_DOMAIN=interview.example.com `
  --env FILES_DOMAIN=files.interview.example.com `
  --env ACME_EMAIL=ops@example.com `
  --volume "${caddyfile}:/etc/caddy/Caddyfile:ro" `
  caddy:2 caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
if ($LASTEXITCODE -ne 0) {
  throw 'Caddy configuration validation failed'
}

$prometheusFiles = @(
  'monitor/prometheus/prometheus.yml',
  'monitor/prometheus/prometheus-prod.yml'
)
foreach ($relativePath in $prometheusFiles) {
  $configPath = (Resolve-Path -LiteralPath (Join-Path $devOps $relativePath)).Path `
    -replace '\\', '/'
  & docker run --rm --entrypoint promtool `
    --volume "${configPath}:/etc/prometheus/prometheus.yml:ro" `
    prom/prometheus:v2.54.1 check config /etc/prometheus/prometheus.yml
  if ($LASTEXITCODE -ne 0) {
    throw "Prometheus configuration validation failed: $relativePath"
  }
}

$logstashConfig = (Resolve-Path -LiteralPath (
    Join-Path $devOps 'observability/logstash/pipeline/logstash.conf')).Path `
  -replace '\\', '/'
& docker run --rm `
  --env LS_JAVA_OPTS='-Xms128m -Xmx256m' `
  --entrypoint /usr/share/logstash/bin/logstash `
  --volume "${logstashConfig}:/usr/share/logstash/pipeline/logstash.conf:ro" `
  docker.elastic.co/logstash/logstash:8.17.0 `
  --config.test_and_exit --path.settings /usr/share/logstash/config `
  -f /usr/share/logstash/pipeline/logstash.conf
if ($LASTEXITCODE -ne 0) {
  throw 'Logstash pipeline validation failed'
}
