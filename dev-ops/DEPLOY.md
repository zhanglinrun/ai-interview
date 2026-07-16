# 4C6G 单机部署、回滚与恢复 Runbook

本 Runbook 面向 5 人以内使用。核心业务、Prometheus/Grafana 与可选轻量 ELK 共用一台
4C6G 主机；ELK 复用业务 Elasticsearch，只写 `ai-interview-logs-*` 独立索引，不启动第二个
Elasticsearch。这里给出的是可复现操作，不代表已经在目标服务器完成 24 小时观察。

## 1. 拓扑与公网边界

- 公网仅开放 `80/443` 和受控 SSH。
- `APP_DOMAIN` 由 Caddy 转发到 frontend nginx，再由 nginx 转发 REST/SSE 到 app。
- `FILES_DOMAIN` 只转发 MinIO API 的 `GET/HEAD/OPTIONS`；桶保持私有，只有短时预签名 URL
  可以读取对象。MinIO 控制台不公开。
- MySQL、Redis、Elasticsearch、RabbitMQ 不发布宿主机端口。
- Prometheus、Grafana、Kibana 仅绑定 `127.0.0.1`，通过 SSH 隧道访问。

生产资源上限是设计预算：核心常驻容器合计 3136 MiB，启用 Prometheus/Grafana 和 `logs`
profile 后合计 4608 MiB。必须以目标机的 `docker stats`、磁盘使用和至少 24 小时观察为准，不能把该预算
写成实测结论。

## 2. 首次准备

在 Ubuntu/Debian 服务器安装 Docker Engine 与 Compose 插件，并设置 Elasticsearch 所需内核参数：

```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-elasticsearch.conf
sudo sysctl --system
```

建议配置 2 GiB swap 只作为构建与瞬时峰值兜底；swap 不能替代内存健康检查。安全组只允许
`80/443`、限定来源的 SSH。两个域名都需要解析到服务器，并满足服务器所在地区的备案要求。

复制并填写配置：

```bash
cd ai-interview/dev-ops
cp .env.prod.example .env
chmod 600 .env
```

至少填写安全密钥、MySQL/MinIO/RabbitMQ 密码、百炼、MinerU、应用域名、文件域名、ACME 邮箱、
`CORS_ALLOWED_ORIGINS`、Grafana 密码与 Kibana 加密密钥。`CORS_ALLOWED_ORIGINS` 必须等于浏览器
实际访问的 origin（只含协议、主机和可选端口，不带路径）；`RABBITMQ_USER` 使用非 `guest` 用户。
Judge0 与 GitHub MCP Token 尚未提供时保持空值/关闭；不得把真实 `.env` 提交到 Git。
`APP_AI_CONFIG_ENCRYPTION_KEY` 在存入用户 BYOK 后不可更换。

## 3. 发布前门禁与 MySQL 最近三份事务快照

先在仓库根目录验证配置：

```powershell
$ErrorActionPreference = 'Stop'
./dev-ops/ci/Test-PowerShellSyntax.ps1
./dev-ops/ci/Test-ReleaseContent.ps1
./dev-ops/ci/Test-ComposeConfig.ps1
./dev-ops/ci/Test-FreshSchema.ps1
./dev-ops/ci/Test-DeploymentAssets.ps1
```

每次部署前在服务器 `dev-ops` 目录执行 MySQL `--single-transaction` 快照。它保证本次数据库导出的
事务一致性，但不是 MySQL、MinIO、Elasticsearch 和 RabbitMQ 的全平台一致性备份。快照包含用户和
加密后的 BYOK 配置，目录权限必须限制为部署账号；下面命令只保留最近 3 份：

```bash
set -euo pipefail
mkdir -p backups/mysql
chmod 700 backups backups/mysql
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
docker compose --env-file .env -f docker-compose-prod.yml exec -T mysql \
  sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --quick --routines --triggers "$MYSQL_DATABASE"' \
  | gzip -9 > "backups/mysql/ai-interview-${stamp}.sql.gz"
chmod 600 "backups/mysql/ai-interview-${stamp}.sql.gz"
ls -1t backups/mysql/ai-interview-*.sql.gz | tail -n +4 | xargs -r rm --
gzip -t "backups/mysql/ai-interview-${stamp}.sql.gz"
```

## 4. 构建与启动

记录待发布 Git SHA。推荐先启动核心业务与 Prometheus / Grafana，不默认让 Logstash / Kibana
占用 4C6G 主机资源：

```bash
set -euo pipefail
git rev-parse HEAD | tee .last-release-sha
docker compose --env-file .env \
  -f docker-compose-prod.yml -f docker-compose-observability.yml \
  config --quiet
docker compose --env-file .env \
  -f docker-compose-prod.yml -f docker-compose-observability.yml \
  up -d --build --remove-orphans
docker compose --env-file .env \
  -f docker-compose-prod.yml -f docker-compose-observability.yml \
  ps
```

核心服务和 Prometheus / Grafana 健康后，再按需启用轻量日志链路。启用前后都要观察
`docker stats` 和磁盘；如果出现持续 swap、OOM 或核心服务延迟，优先关闭 Logstash / Kibana：

```bash
set -euo pipefail
docker compose --env-file .env \
  -f docker-compose-prod.yml -f docker-compose-observability.yml \
  --profile logs config --quiet
docker compose --env-file .env \
  -f docker-compose-prod.yml -f docker-compose-observability.yml \
  --profile logs up -d --remove-orphans
```

服务健康后，可从服务器或一台可信的 PowerShell 7 客户端运行基础 smoke：

```powershell
$ErrorActionPreference = 'Stop'
./smoke-test.ps1 -BaseUrl "https://$env:APP_DOMAIN" `
  -FilesBaseUrl "https://$env:FILES_DOMAIN"
```

`smoke-test.ps1` 默认只验证边缘健康和私有文件域，不创建业务数据。全业务接线 smoke 使用进程级
`SMOKE_*` 输入；不要把 BYOK Key 写进命令参数、脚本或报告：

```powershell
$ErrorActionPreference = 'Stop'
$env:SMOKE_BYOK_API_KEY = '<仅在当前可信终端设置>'
$env:SMOKE_BYOK_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
$env:SMOKE_BYOK_MODEL = 'qwen3.5-flash'
$env:SMOKE_GITHUB_REPOSITORY_URL = 'https://github.com/owner/public-repository'
$env:SMOKE_DOCUMENT_PATH = '/absolute/path/to/non-sensitive-fixture.pdf'
$env:SMOKE_DOCUMENT_QUESTION = '这份文档的核心设计是什么？'

./smoke-test.ps1 -RequireFullFlow `
  -BaseUrl "https://interview.example.com" `
  -FilesBaseUrl "https://files.interview.example.com" `
  -ReportPath './reports/release-smoke.json'

$env:SMOKE_BYOK_API_KEY = $null
```

全业务接线 smoke 真实执行注册/登录、BYOK 连通性、MinerU PDF 解析与 RAG、JD 分析冻结、GitHub 固定 SHA、
岗位实战 REST/SSE、Judge0、报告/画像/训练和 LLM Usage 可见性。缺少输入、MinerU 发生 Tika 降级、
Judge0 返回待补判或任何阶段跳过时，`-RequireFullFlow` 都会失败关闭，不能宣称真实 E2E 通过。报告只
保存业务 ID、状态和计数，不保存 Key、Prompt、回答、源码、隐藏用例或签名 URL。默认清理 BYOK、
文档和 GitHub 绑定，并对冻结 JD 做脱敏删除；保留 smoke 用户、会话、报告和训练记录作为验收证据。

这个脚本验证的是跨模块接线，不是完整 45 分钟产品验收：它只回答一道岗位题后主动结束会话，
Judge0 使用独立训练 attempt；它不覆盖四阶段完整作答、岗位算法阶段、DOCX / HTML 的真实 MinerU 解析、
24 小时续面、GitHub MCP 确实命中、Redis / RabbitMQ / 容器重启和客户端中途断线恢复。上述场景必须
另行执行并保存脱敏记录，不能用接线 smoke 的 `PASS` 代替。

## 5. 私有文件域与 SSE 验证

```bash
# 前端/Caddy 健康
curl --fail --silent --show-error "https://${APP_DOMAIN}/healthz"

# 公网写 MinIO 必须被 Caddy 拒绝（预期 405）
curl -sS -o /dev/null -w '%{http_code}\n' -X PUT "https://${FILES_DOMAIN}/forbidden"

# 未签名对象不可读取（预期 403 或 404，不得为 200）
curl -sS -o /dev/null -w '%{http_code}\n' \
  "https://${FILES_DOMAIN}/${MINIO_BUCKET}/unsigned-object"
```

SSE 用真实文字面试或资料学习请求验证：首个事件应持续到达，Caddy/nginx 不应攒批。不要把
Authorization、Prompt 或回答复制到日志或验收报告。

## 6. 观测与日志

从本机建立隧道：

```bash
ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 \
  -L 5601:127.0.0.1:5601 deploy@server
```

- Grafana：`http://127.0.0.1:3000`
- Prometheus：`http://127.0.0.1:9090`
- Kibana：`http://127.0.0.1:5601`
- 应用滚动日志：`app_logs` 卷，单文件 20 MiB、最多 7 天、总上限 300 MiB。
- 容器 stdout：单文件 10 MiB、3 份。
- Prometheus：默认 7 天且最多 512 MiB。
- 日志 ES 索引：`ai-interview-logs-*`，默认 7 天 ILM。

抽检日志时确认没有 Key、Authorization、完整 Prompt/回答、简历/资料正文、源码、隐藏用例和
预签名 URL。日志索引与 RAG 向量索引共享 ES 进程，但索引名称、生命周期策略相互独立。

## 7. 回滚

应用回滚优先恢复上一版本代码/镜像，不先回滚数据：

```bash
set -euo pipefail
previous_sha='<已验证的上一版本 SHA>'
git switch --detach "$previous_sha"
docker compose --env-file .env \
  -f docker-compose-prod.yml -f docker-compose-observability.yml \
  --profile logs up -d --build --remove-orphans
```

确认 smoke 和健康状态后再决定是否回到分支。生产 `.env` 与
`APP_AI_CONFIG_ENCRYPTION_KEY` 必须保持不变。

只有本次发布已经写入不兼容数据时才恢复 MySQL 快照。先停止 app 和异步消费者，再重建数据库并
导入指定快照：

```bash
set -euo pipefail
snapshot='backups/mysql/ai-interview-YYYYmmddTHHMMSSZ.sql.gz'
gzip -t "$snapshot"
docker compose --env-file .env -f docker-compose-prod.yml stop app
docker compose --env-file .env -f docker-compose-prod.yml exec -T mysql \
  sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`; CREATE DATABASE \`$MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"'
gzip -dc "$snapshot" | docker compose --env-file .env -f docker-compose-prod.yml exec -T mysql \
  sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$MYSQL_DATABASE"'
docker compose --env-file .env \
  -f docker-compose-prod.yml -f docker-compose-observability.yml \
  --profile logs up -d
```

恢复后逐项验证 MySQL 记录引用的 MinIO 对象仍存在，再按业务提供的重建入口重新向量化 ES。这里没有
创建 MinIO 历史快照，因此已经删除的对象不能靠 MySQL dump 恢复；缺失对象需要重新上传或将对应记录
标记为来源不可用。Redis 是短期运行态，丢失后由 MySQL 恢复会话事实；RabbitMQ 重启后依靠持久消息、
幂等状态与补偿任务收敛。未实际演练前，这些只能表述为恢复设计，不能表述为恢复实测。

## 8. 24 小时观察清单

记录时间段、Git SHA、Compose 配置摘要和脱敏结果：

- `docker stats` 是否出现 OOM、持续 swap 或 CPU 饥饿。
- 数据盘、Docker 日志、Prometheus、应用滚动日志和 ES 日志索引增量。
- app、RabbitMQ、Redis、ES、MinIO 单容器重启后的恢复表现。
- MinerU/Judge0/GitHub 超时与降级、SSE 断线恢复、RabbitMQ DLQ。
- 5 个以内账号的数据隔离、删除与 BYOK 解密。

没有目标服务器凭据时，本仓库只能完成 Compose/config/镜像门禁，不能勾选真实 HTTPS、真实外部
服务 E2E、恢复演练和 24 小时稳定性验收。
