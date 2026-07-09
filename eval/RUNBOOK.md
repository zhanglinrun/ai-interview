# 评测与压测 Runbook

把「功能可用」变成「数字可追踪」：一次跑完压测（k6）、RAG 检索评测、RAGAS 生成质量评测、图谱检索评测与统一评测闭环，并把结果回填到根 `README.md` 的对应表格。

所有评测都需要真实依赖（MySQL / Redis / ES / Neo4j / DashScope），本机 Docker 起一套即可。产物写到各自的 `.work/` 临时目录（不进 git），把关键数字誊进 README。

---

## 0. 前置

| 工具 | 用途 | 安装 |
| --- | --- | --- |
| Docker / Docker Compose | 拉起 MySQL/Redis/ES/Neo4j/MinIO/(RocketMQ 或 RabbitMQ) | Docker Desktop |
| JDK 21 + Maven 3.9+ | 后端 + JUnit 评测 | - |
| [k6](https://k6.io/docs/get-started/installation/) | 压测 | `winget install k6` / `brew install k6` |
| Python 3.11+ + [uv](https://docs.astral.sh/uv/) | RAGAS 评测 | `pip install uv` |
| DashScope API Key | LLM / Embedding / 评测 judge | 阿里云百炼控制台 |

DashScope Key 必填：`.env` 里 `AI_BAILIAN_API_KEY=sk-...`。

---

## 1. 起依赖 + 后端

```bash
cp .env.example .env          # 填 AI_BAILIAN_API_KEY、APP_JWT_SECRET（≥32 字节）
cd dev-ops && docker compose -f docker-compose-environment.yml up -d
cd ../backend && mvn spring-boot:run
```

- 后端 `http://localhost:8082`，Swagger `http://localhost:8082/swagger-ui.html`。
- Windows/WSL2 若 RocketMQ 9876 连不通：`.env` 设 `APP_ASYNC_ENGINE=rabbitmq`（compose 已含 RabbitMQ，管理台 `http://localhost:15672`，guest/guest）。若 5672/15672 被其他项目占用（如 ai-group），在 `.env` 同步改 `RABBITMQ_PORT` / `RABBITMQ_HOST_PORT` / `RABBITMQ_MGMT_HOST_PORT`（本机当前为 25672/25673）。
- 首次启动会触发 `SkillGraphBootstrap` 预置技能图谱（供第 6 步图谱评测）。
- **MySQL 卷是旧的（升级前建过库）**：先跑一次存量库升级脚本（幂等，补 `rag_query_traces` 的查询分解/CRAG/图谱共 6 列 + 版本表去重索引），否则 RAG Trace 会因缺列静默丢失（问答不受影响，只剩 warn 日志）。端口/库名/账号以 `.env` 为准：
  `mysql -h127.0.0.1 -P33306 -u<MYSQL_USER> -p <MYSQL_DB> < backend/src/main/resources/sql/upgrade/2026-07-graph-trace-dedup.sql`

---

## 2. 拿一个 JWT（压测/RAGAS 都要）

注册 + 登录，取 `data.accessToken`：

```bash
curl -s -X POST http://localhost:8082/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"loadtest","password":"loadtest123","email":"lt@example.com"}'

curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"loadtest","password":"loadtest123"}'
# → { "data": { "accessToken": "eyJ...", ... } }
```

k6 脚本支持两种鉴权：直接 `-e TOKEN=eyJ...`，或 `-e AUTH_USER=loadtest -e AUTH_PASSWORD=loadtest123`（脚本 `setup()` 自动登录，见 `eval/loadtest/helpers.js`）。

---

## 3. 播种一个知识库（RAG 相关评测前置）

用第 2 步账号，上传 → 切块 → 等向量化（异步，切块后自动触发）：

```bash
TOKEN=eyJ...
# 上传（DOCUMENT_SEARCH 类型）
curl -s -X POST "http://localhost:8082/api/knowledgebase/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/redis.pdf" -F "category=Java面试"
# → data 为 docId；随后切块
curl -s -X POST "http://localhost:8082/api/knowledgebase/$DOC_ID/split" -H "Authorization: Bearer $TOKEN"
# 轮询直到 docStatus=VECTOR_STORED
curl -s "http://localhost:8082/api/knowledgebase/$DOC_ID" -H "Authorization: Bearer $TOKEN"
```

RAG 检索/RAGAS 评测集 `eval/rag-retrieval/eval-dataset.yaml` 按 `source`（redis/mysql/distributed/jvm/spring）分组，需分别建 5 个知识库并把 id 写进 `RAGEVAL_KB_*` 环境变量（见第 5、6 步）。语料 PDF 放 `eval/corpus/`（本地，不进 git）。

---

## 4. k6 压测

脚本在 `eval/loadtest/`。统一约定：`-e BASE_URL=` + 鉴权（`-e TOKEN=` 或 `-e AUTH_USER=/-e AUTH_PASSWORD=`）。看 summary 里的 `http_req_duration p(95)/p(99)`、`http_reqs`（QPS）、各脚本自定义 `*_fail` 失败率。

| 脚本 | 压什么 | 命令 | 回填 README |
| --- | --- | --- | --- |
| `kb-list-throughput.js` | 纯后端吞吐（不打 LLM，真·QPS/P99） | `k6 run -e BASE_URL=http://localhost:8082 -e AUTH_USER=loadtest -e AUTH_PASSWORD=loadtest123 -e VUS=50 -e DURATION=1m eval/loadtest/kb-list-throughput.js` | QPS、P95/P99 |
| `rag-query.js` | RAG 非流式全链路延迟 | `k6 run -e AUTH_USER=.. -e AUTH_PASSWORD=.. -e KB_IDS=1 eval/loadtest/rag-query.js` | 端到端 P95/P99、失败率 |
| `sse-ttft.js` | 流式首字延迟（TTFT 下界代理） | `k6 run -e AUTH_USER=.. -e AUTH_PASSWORD=.. -e KB_IDS=1 eval/loadtest/sse-ttft.js` | `ttft_proxy_ms p(95)` |
| `interview-create.js` | 出题（Skill）延迟 | `k6 run -e AUTH_USER=.. -e AUTH_PASSWORD=.. -e SKILL_ID=java-backend eval/loadtest/interview-create.js` | P95/P99 |
| `agent-ab.js` | Multi-Agent 出题（Critic 开/关 A/B） | 分别以 `APP_AI_AGENT_CRITIC_ENABLED=true/false` 重启后端各跑一次 | 两组延迟对比 |

提示：LLM 接口有 `@RateLimit`（如 query GLOBAL/IP=10、stream=5、出题=5）。压「系统极限」时临时调高限流或用低并发采样；压「真实带限流表现」时保留并把限流计入失败率。缓存穿透防护对照：`-e MODE=missing` 跑 `kb-list-throughput.js`。

**首个 LLM token 精确 TTFT**（k6 的 TTFB 是下界）：用 `curl -N` 抓第一条 `token` 事件时间戳，命令见 `sse-ttft.js` 顶部注释。

---

## 5. RAG 检索评测（Hit@K / MRR / NDCG）

`RagRetrievalEvalTest`（默认被 `rag-eval` 组排除）。前置：5 个语料知识库已 VECTOR_STORED。

```bash
export RAGEVAL_KB_REDIS=9 RAGEVAL_KB_MYSQL=10 RAGEVAL_KB_DISTRIBUTED=11 RAGEVAL_KB_JVM=12 RAGEVAL_KB_SPRING=13
mvn -pl backend test -Dtest=RagRetrievalEvalTest '-Dtest.excludedGroups=' -Dgroups=rag-eval
```

报告：`eval/.work/rag-retrieval-report.md`。回填 README「RAG 检索评测结果」表（vector / hybrid+rerank+expand 各变体的覆盖率/命中率/MRR/NDCG）。CI 门禁：设 `RAGEVAL_MIN_COVERAGE` 后最优档低于阈值断言失败。

---

## 6. RAGAS 生成质量评测（faithfulness 等）

```bash
export RAGEVAL_JWT=eyJ... DASHSCOPE_API_KEY=sk-...
export RAGEVAL_KB_REDIS=9 RAGEVAL_KB_MYSQL=10 RAGEVAL_KB_DISTRIBUTED=11 RAGEVAL_KB_JVM=12 RAGEVAL_KB_SPRING=13
cd eval/ragas && uv sync
uv run run_ragas.py --limit 20          # 走完整 RAG 生成导出 QA 再评测
# 已有导出：uv run run_ragas.py --from-jsonl .work/qa-export-xxx.jsonl
```

报告：`eval/ragas/.work/ragas-report-*.md`。回填 README「RAGAS 生成质量基线」表（faithfulness / answer_relevancy / context_precision / context_recall）。

---

## 7. 图谱检索评测（技能→概念 覆盖度）

`GraphRetrievalEvalTest`（默认被 `graph-eval` 组排除）。前置：后端至少启动过一次（触发技能图谱预置），或已导入图数据。

```bash
export NEO4J_URI=bolt://localhost:27687 NEO4J_USER=neo4j NEO4J_PASSWORD=neo4j666
mvn -pl backend test -Dtest=GraphRetrievalEvalTest '-Dtest.excludedGroups=' -Dgroups=graph-eval
```

报告：`eval/.work/graph-eval-report.md`（Skill/Concept 节点数、RELATES_TO 边、每技能覆盖概念数）。CI 门禁：`GRAPH_EVAL_MIN_SKILLS` / `GRAPH_EVAL_MIN_COVERAGE`。前端「知识图谱」页（`/knowledge-graph`）可视化同一份图。

---

## 8. 统一评测闭环（意图 + RAG + LLM-as-Judge + 基线回归）

前端「统一评测」页（`/eval`）点「加载示例 → 运行评测」最直观；或 curl：

```bash
curl -s -X POST http://localhost:8082/api/eval/run \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d @eval/unified-eval-example.json   # 结构见 README「统一评测闭环」最小请求示例
```

每次运行落 `eval_runs`，可传 `baselineKey` + `updateBaseline` 做版本回归对比。

---

## 9. 回填 README 的清单

| 评测 | 产物 | README 章节 |
| --- | --- | --- |
| k6 压测 | k6 summary（P95/P99/QPS/TTFT） | 新增「性能压测」表（QPS、P99、TTFT、限流表现） |
| RAG 检索 | `eval/.work/rag-retrieval-report.md` | 「RAG 检索评测结果」 |
| RAGAS | `eval/ragas/.work/ragas-report-*.md` | 「RAGAS 生成质量基线」 |
| 图谱检索 | `eval/.work/graph-eval-report.md` | 新增「图谱检索评测」小节 |

> 写进简历/README 的每个数字，都要保留对应报告与一次可复现运行（命令 + 环境），面试被追问时能当场复现。
