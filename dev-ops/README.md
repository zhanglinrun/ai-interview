# AI Interview 部署入口

本目录提供本地依赖、全栈开发和 4C6G 单机生产编排。真实密钥只写本机或服务器的 `.env`，
不要写入 Compose、脚本、提交记录或 smoke 报告。

## 编排选择

| 场景 | Compose 文件 | 说明 |
| --- | --- | --- |
| 本地依赖 | `docker-compose-environment.yml` | MySQL、Redis、ES、MinIO、RabbitMQ |
| 本地全栈 | `docker-compose-app.yml` | 依赖、后端和前端 |
| 本地监控 | `docker-compose-monitor.yml` | 叠加 Prometheus / Grafana |
| 临时 IP | `docker-compose-ip.yml` | HTTP 引导模式，不用于真实 BYOK / MinerU 验收 |
| HTTPS 生产 | `docker-compose-prod.yml` | Caddy 双域名、核心业务和基础设施 |
| 生产观测 | `docker-compose-observability.yml` | Prometheus / Grafana；`logs` profile 启用 Logstash / Kibana |

生产常驻容器 memory limit 合计 3136 MiB；叠加 Prometheus、Grafana、Logstash 和 Kibana 后为
4608 MiB。它们是设计上限，不是目标服务器实测数据。

## 发布前检查

在仓库根目录运行：

```powershell
$ErrorActionPreference = 'Stop'
./dev-ops/ci/Test-PowerShellSyntax.ps1
./dev-ops/ci/Test-ReleaseContent.ps1
./dev-ops/ci/Test-ComposeConfig.ps1
./dev-ops/ci/Test-FreshSchema.ps1
./dev-ops/ci/Test-DeploymentAssets.ps1
```

上述门禁验证能力/Hot 100 内容、fresh schema、所有 Compose 拓扑、4C6G 资源与端口策略、Caddy、
Prometheus、Grafana JSON 和 Logstash 配置。它们不替代真实外部 API、HTTPS、恢复演练或 24 小时
资源观察。

## 已有数据卷升级

MySQL 的 `/docker-entrypoint-initdb.d/01-schema.sql` 只在空数据卷首次初始化时执行。代码更新如果
新增了 `backend/src/main/resources/sql/upgrade/` 下的脚本，复用旧数据卷时必须先运行：

```powershell
$ErrorActionPreference = 'Stop'
./dev-ops/Apply-DatabaseUpgrades.ps1
```

升级脚本按文件名顺序执行，并使用 `information_schema` 保证可重复运行。脚本只读取本地 `.env`
中的数据库名和 root 密码，不输出密码。应先备份生产数据库，再执行升级和后端发布。

## 生产部署

完整的首次部署、私有文件域、基础与全业务 smoke、短期 MySQL 快照、回滚和恢复步骤见
[DEPLOY.md](DEPLOY.md)。生产模板为 [.env.prod.example](.env.prod.example)。
