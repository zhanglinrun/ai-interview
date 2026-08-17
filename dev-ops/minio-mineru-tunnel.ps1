$ErrorActionPreference = 'Stop'

# 把本机 Docker MinIO API 打成 Cloudflare 快速隧道，写出 MINIO_EXTERNAL_ENDPOINT。
# 官方 MinerU 只能拉公网 HTTPS；解析完请关掉隧道。

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$compose = Join-Path $PSScriptRoot 'docker-compose-minio-tunnel.yml'
$workDir = Join-Path $repoRoot 'eval\rag\.work'
$outFile = Join-Path $workDir 'minio-external.env'

New-Item -ItemType Directory -Force -Path $workDir | Out-Null

docker compose --project-directory $PSScriptRoot --env-file (Join-Path $repoRoot '.env') `
  -f $compose up -d
if ($LASTEXITCODE -ne 0) {
  throw 'failed to start interview-minio-tunnel'
}

$url = $null
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Seconds 2
  $logs = cmd /c "docker logs interview-minio-tunnel 2>&1"
  if ($logs -match 'https://[a-z0-9-]+\.trycloudflare\.com') {
    $url = $Matches[0].TrimEnd('/')
    break
  }
}

if (-not $url) {
  throw 'tunnel started but no trycloudflare.com URL appeared in logs'
}

"MINIO_EXTERNAL_ENDPOINT=$url" | Set-Content -Encoding utf8 $outFile
Write-Host "MINIO_EXTERNAL_ENDPOINT=$url"
Write-Host "wrote $outFile"
Write-Host "把同一行写入仓库根目录 .env 后重启后端。解析完成后执行: docker compose -f dev-ops/docker-compose-minio-tunnel.yml down"
