# 监控配置（Prometheus + Grafana）

配合 [`../../eval/loadtest/`](../../eval/loadtest/) 压测时使用：采集后端 `/actuator/prometheus`，在 Grafana 展示 RAG / Agent / 异步管道看板。

## 启动

**仅监控**（后端需已在宿主机或全栈容器中运行）：

```bash
cd dev-ops
docker compose -f docker-compose-monitor.yml up -d
```

**依赖 + 监控**（本机 `mvn spring-boot:run` 场景）：

```bash
cd dev-ops
docker compose -f docker-compose-environment.yml -f docker-compose-monitor.yml up -d
```

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000（默认 admin / admin）

## 采集目标

`prometheus/prometheus.yml` 预置两个 job：

| Job | 目标 | 场景 |
| --- | --- | --- |
| `ai-interview-backend` | `app:8080` | 全栈 `docker-compose-app.yml`，后端在 compose 网络内 |
| `ai-interview-backend-local` | `host.docker.internal:8082` | 宿主机 `mvn spring-boot:run`（默认端口 8082） |

本地开发一般看第二个 target 是否为 UP。

## Agent 相关指标

- `app_ai_agent_question_latency_seconds_bucket`
- `app_ai_agent_plan_latency_seconds_bucket`
- `app_ai_agent_critic_verdicts_total`
- `app_ai_agent_reflexion_rounds_count/sum`

更多说明见 [`../../eval/loadtest/README.md`](../../eval/loadtest/README.md)。
