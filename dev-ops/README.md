# AI Interview 本地 Docker 入口

本目录只保留本地开发需要的四个 Compose 入口。真实密钥只写根目录 `.env`，不要提交。

## 编排选择

| 场景 | Compose 文件 | 说明 |
| --- | --- | --- |
| 本地依赖 | `docker-compose-environment.yml` | MySQL、Redis、Elasticsearch、MinIO、RabbitMQ、Neo4j；Neo4j 默认启动 |
| 本地全栈 | `docker-compose-app.yml` | 一键启动依赖、XXL-Job、后端和前端 |
| 日志查询 | `docker-compose-elk.yml` | Logstash + Kibana，连接本地 Elasticsearch |
| 指标监控 | `docker-compose-grafana.yml` | Prometheus + Grafana |

## 本地全栈启动

`docker-compose-app.yml` 是当前验收入口，包含 RAG 所需的三类数据源：Elasticsearch、MySQL
和 Neo4j；同时包含 MinIO 原文件存储、RabbitMQ 异步任务、Redis 会话/缓存、XXL-Job 补偿调度、
Spring Boot 后端和 Nginx 前端。默认使用独立的 `ai_interview_v2` 数据库，不复用旧库中的表结构。

在仓库根目录执行：

```powershell
$env:RABBITMQ_HOST_PORT = '35672'       # 如果本机 5672 已被其他项目占用
$env:RABBITMQ_MGMT_HOST_PORT = '35673'  # 如果本机 15672 已被其他项目占用
docker compose --project-directory dev-ops --env-file .env `
  -f dev-ops/docker-compose-app.yml up -d --build
docker compose --project-directory dev-ops --env-file .env `
  -f dev-ops/docker-compose-app.yml ps
```

默认访问地址：

| 组件 | 地址 |
| --- | --- |
| 前端 | `http://localhost:28080` |
| 后端健康检查 | `http://localhost:28082/actuator/health` |
| XXL-Job Admin | `http://localhost:28081/xxl-job-admin/` |
| MinIO Console | `http://localhost:29001` |
| Neo4j Browser | `http://localhost:27474` |
| Elasticsearch | `http://localhost:29200` |

MinIO 桶由后端首次使用时按需创建，不额外启动一次性初始化容器；Neo4j 直接随依赖服务启动。
若 RabbitMQ 默认端口没有冲突，可省略两个端口覆盖变量。

需要单独打开日志和监控时，只使用另外两个固定入口：

```powershell
docker compose --project-directory dev-ops --env-file .env `
  -f dev-ops/docker-compose-elk.yml up -d
docker compose --project-directory dev-ops --env-file .env `
  -f dev-ops/docker-compose-grafana.yml up -d
```

这两个入口不再通过 profile 控制；Elasticsearch 由 `app` / `environment` 提供，Neo4j 由
`app` / `environment` 直接启动。

## 目录结构

| 路径 | 用途 |
| --- | --- |
| `docker-compose-app.yml` | 一键全栈：依赖、后端、前端 |
| `docker-compose-environment.yml` | IDEA 本地调试依赖 |
| `docker-compose-elk.yml` | Logstash + Kibana |
| `docker-compose-grafana.yml` | Prometheus + Grafana |
| `docker/` | MySQL/XXL-Job、Grafana、Prometheus、Logstash 的运行时资源 |
| `ci/` | 内容、Schema、Compose 和脚本门禁 |
| `start.*`、`stop.*` | 启停快捷脚本 |

## 本地检查

在仓库根目录运行：

```powershell
$ErrorActionPreference = 'Stop'
./dev-ops/ci/Test-PowerShellSyntax.ps1
./dev-ops/ci/Test-ReleaseContent.ps1
./dev-ops/ci/Test-ComposeConfig.ps1
./dev-ops/ci/Test-FreshSchema.ps1
```

上述门禁验证能力目录、Hot 100 内容、fresh schema 和四个 Compose 入口。

## V2 数据初始化策略

V2 不迁移旧数据，也不保留旧表兼容脚本。需要重建本地环境时，删除 MySQL/ES/Redis 数据卷并重新
执行 `schema.sql`；Compose 会在全新 MySQL 卷首次启动时自动初始化完整结构。
