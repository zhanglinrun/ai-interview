# 压测脚本（k6）

用 [k6](https://k6.io/) 压关键接口，拿到 P95/P99 延迟和吞吐（QPS），并做几组前后对比，给简历里的性能数字提供依据。

## 装 k6

```bash
# Windows (winget)
winget install k6 --source winget
# macOS
brew install k6
# 或用 Docker，免安装
docker run --rm -i grafana/k6 run - < loadtest/rag-query.js
```

## 脚本一览

| 脚本 | 压的接口 | 覆盖链路 |
| --- | --- | --- |
| `rag-query.js` | `POST /api/knowledgebase/query` | 查询改写 + 混合检索（向量 + 关键词 RRF）+ rerank + LLM 生成 |
| `interview-create.js` | `POST /api/interview/sessions` | Skill 出题（同步生成题目） |

两个脚本都支持两种负载档位：
- 默认走内置 `stages`（逐步加压到峰值再回落），适合看延迟随并发的变化。
- 传 `VUS` + `DURATION` 走固定并发，适合做 A/B 对比时保证两次负载一致。

## 怎么跑

先把后端和依赖起起来（`docker compose -f docker-compose.dev.yml up -d` + `mvn -pl backend spring-boot:run`），知识库里至少要有一个已向量化完成的文档，拿到它的 `kbId`。

```bash
# 先登录拿到 accessToken，再传给 TOKEN
# PowerShell: $env:TOKEN="你的 accessToken"
# bash: export TOKEN="你的 accessToken"

# RAG 问答，默认档位
k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=$TOKEN -e KB_IDS=1 loadtest/rag-query.js

# RAG 问答，固定 20 并发压 2 分钟
k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=$TOKEN -e KB_IDS=1 -e VUS=20 -e DURATION=2m loadtest/rag-query.js

# 面试出题
k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=$TOKEN -e SKILL_ID=java-backend loadtest/interview-create.js
```

跑完看 summary 里的 `http_req_duration`（含 p95/p99/max）和 `http_reqs`（吞吐），以及自定义的 `*_fail` 业务失败率。

> 接口带 `@RateLimit`（RAG 查询 GLOBAL=10/IP=10，面试创建 GLOBAL=5/IP=5）。压**系统极限**时，临时把对应注解的 count 调大或注释掉再重启；压**真实带限流表现**时保留，观察失败率里限流占比。两种结论分开记。

## 对比实验（简历里的"前后对比"就来自这里）

### A. 虚拟线程开 / 关

项目默认开了虚拟线程（`spring.threads.virtual.enabled=true`）。RAG 问答和出题都是 I/O 密集（等 LLM、等向量库），是虚拟线程的典型受益场景。

```bash
# 开（默认）
mvn -pl backend spring-boot:run
k6 run -e KB_IDS=1 -e VUS=20 -e DURATION=2m loadtest/rag-query.js   # 记下 p99 / QPS

# 关：临时用环境变量覆盖后重启
# PowerShell:  $env:SPRING_THREADS_VIRTUAL_ENABLED="false"; mvn -pl backend spring-boot:run
# bash:        SPRING_THREADS_VIRTUAL_ENABLED=false mvn -pl backend spring-boot:run
k6 run -e KB_IDS=1 -e VUS=20 -e DURATION=2m loadtest/rag-query.js   # 再记一次，对比
```

两次用**相同的 VUS/DURATION**，对比 P99 和吞吐。

### B. 批量向量化吞吐

知识库向量化已迁至 Spring 事件 + 补偿任务链路（`DocumentChunkedEvent` → `@Async` 嵌入写 ES），无文档级并行度可调。用批量上传接口压，从 Grafana 的「异步管道」面板观察 Stream 积压清空速度，或看后端日志里每个文档的完成时间。

## 配合监控看

压测时打开 Grafana（`docker compose -f docker-compose.monitor.yml up -d`，见 `docker-compose.monitor.yml` 头部说明），「AI Interview - RAG / 异步管道 / 稳定性」看板能同时看到压测期间的检索延迟、QPS、Stream 积压、首 token 延迟曲线。截图配合 k6 的数字一起放简历/文档里更有说服力。

## 把结果记下来

建议每次对比都按这个格式记一行，简历里就能写成「指标 + 手段 + 前后对比」：

```
场景            并发  P50    P95    P99     QPS    失败率
RAG 问答(VT 开)  20   1.2s   3.8s   6.1s    14.2   0%
RAG 问答(VT 关)  20   1.5s   5.9s   11.3s   9.1    0%
```
