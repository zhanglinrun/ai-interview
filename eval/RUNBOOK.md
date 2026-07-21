# 评测与压测 Runbook

把「功能可用」变成「数字可追踪」：依次运行压测（k6）、RAG 检索评测、RAGAS 生成质量评测、
Agent Critic 质量门与统一评测，并把结果回填到根 `README.md` 的对应表格。

检索评测需要 MySQL / Redis / Elasticsearch / MinIO 和平台 Embedding；生成与 Agent 评测还需要可用
模型 Key。产物写到各自的 `.work/` 临时目录（不进 git），把关键数字和运行参数一起保存。

---

## 0. 前置

| 工具 | 用途 | 安装 |
| --- | --- | --- |
| Docker / Docker Compose | 拉起 MySQL / Redis / Elasticsearch / MinIO / RabbitMQ | Docker Desktop |
| JDK 21 + Maven 3.9+ | 后端 + JUnit 评测 | - |
| [k6](https://k6.io/docs/get-started/installation/) | 压测 | `winget install k6` / `brew install k6` |
| Python 3.11+ + [uv](https://docs.astral.sh/uv/) | RAGAS 评测 | `pip install uv` |
| DashScope API Key | LLM / Embedding / 评测 judge | 阿里云百炼控制台 |

DashScope Key 必填：`.env` 里 `AI_BAILIAN_API_KEY=sk-...`。

---

## 1. 起依赖 + 后端

```powershell
$ErrorActionPreference = 'Stop'
Copy-Item .env.example .env
docker compose -f dev-ops/docker-compose-environment.yml up -d
./dev-ops/Apply-DatabaseUpgrades.ps1
mvn -pl backend spring-boot:run
```

- 后端 `http://localhost:8082`，Swagger `http://localhost:8082/swagger-ui.html`。
- RabbitMQ 开发端口以 `.env` 和 Compose 为准；本仓库当前本地映射为 25672 / 25673。
- 复用旧 MySQL 卷时必须运行 `dev-ops/Apply-DatabaseUpgrades.ps1`。只修改 `schema.sql` 不会升级
  已存在的数据卷。

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

```powershell
$ErrorActionPreference = 'Stop'
$env:TOKEN = 'eyJ...'
# 上传（DOCUMENT_SEARCH 类型）
curl.exe -s -X POST "http://localhost:8082/api/knowledgebase/upload" `
  -H "Authorization: Bearer $env:TOKEN" `
  -F "file=@E:/path/to/redis.pdf" -F "category=Java面试"
# → data 为 docId；随后切块
curl.exe -s -X POST "http://localhost:8082/api/knowledgebase/$env:DOC_ID/split" `
  -H "Authorization: Bearer $env:TOKEN"
# 轮询直到 docStatus=VECTOR_STORED
curl.exe -s "http://localhost:8082/api/knowledgebase/$env:DOC_ID" `
  -H "Authorization: Bearer $env:TOKEN"
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
| `agent-ab.js` | Agent 工作流出题（Critic/Reflection 开关 A/B） | 分别以 `APP_AI_AGENT_CRITIC_ENABLED=true/false` 重启后端各跑一次 | 两组延迟与失败率对比 |

提示：LLM 接口有 `@RateLimit`（如 query GLOBAL/IP=10、stream=5、出题=5）。压「系统极限」时临时调高限流或用低并发采样；压「真实带限流表现」时保留并把限流计入失败率。缓存穿透防护对照：`-e MODE=missing` 跑 `kb-list-throughput.js`。

**首个 LLM token 精确 TTFT**（k6 的 TTFB 是下界）：用 `curl -N` 抓第一条 `token` 事件时间戳，命令见 `sse-ttft.js` 顶部注释。

---

## 5. RAG 检索评测（Hit@K / MRR / NDCG）

`RagRetrievalEvalTest`（默认被 `rag-eval` 组排除）。前置：5 个语料知识库已 VECTOR_STORED。

```powershell
$ErrorActionPreference = 'Stop'
$env:RAGEVAL_KB_REDIS = '9'
$env:RAGEVAL_KB_MYSQL = '10'
$env:RAGEVAL_KB_DISTRIBUTED = '11'
$env:RAGEVAL_KB_JVM = '12'
$env:RAGEVAL_KB_SPRING = '13'
mvn -pl backend test -Dtest=RagRetrievalEvalTest '-Dtest.excludedGroups=' -Dgroups=rag-eval
```

报告：`eval/.work/rag-retrieval-report.md`。回填 README「RAG 检索评测结果」表（vector / hybrid+rerank+expand 各变体的覆盖率/命中率/MRR/NDCG）。CI 门禁：设 `RAGEVAL_MIN_COVERAGE` 后最优档低于阈值断言失败。

---

## 6. RAGAS 生成质量评测（faithfulness 等）

```powershell
$ErrorActionPreference = 'Stop'
$env:RAGEVAL_JWT = 'eyJ...'
$env:DASHSCOPE_API_KEY = 'sk-...'
$env:RAGEVAL_KB_REDIS = '9'
$env:RAGEVAL_KB_MYSQL = '10'
$env:RAGEVAL_KB_DISTRIBUTED = '11'
$env:RAGEVAL_KB_JVM = '12'
$env:RAGEVAL_KB_SPRING = '13'
Set-Location eval/ragas
uv sync
uv run run_ragas.py --limit 20          # 走完整 RAG 生成导出 QA 再评测
# 已有导出：uv run run_ragas.py --from-jsonl .work/qa-export-xxx.jsonl
```

报告：`eval/ragas/.work/ragas-report-*.md`。回填 README「RAGAS 生成质量基线」表（faithfulness / answer_relevancy / context_precision / context_recall）。

---

## 7. Agent Critic 质量门

```powershell
$ErrorActionPreference = 'Stop'
$env:AI_BAILIAN_API_KEY = 'sk-...'
$env:CRITIC_EVAL_MIN_ACCURACY = '0.80'
mvn -pl backend '-Dtest=InterviewCriticEvalTest' '-Dtest.excludedGroups=' '-Dgroups=agent-eval' test
```

报告写入 `eval/.work/critic-badcase-report.md`。它验证 Critic 能否拦截越界、重复、含糊和 Prompt
Injection bad case；不等同于证明完整 Agent 的业务完成率。

2026-07-18 当前代码实测：qwen3.5-flash，13 条用例，9 个 bad case 全部打回、4 个正例全部放行，
Accuracy / 打回 Precision / Recall / F1 均为 1.00，运行耗时 338.1 秒。报告保存在本机
`eval/.work/critic-badcase-report.md`；更换模型、Prompt 或数据集后必须重新运行。

---

## 8. 统一评测闭环（意图 + RAG + 回答质量 + 基线回归）

前端 `/eval` 页面点击“加载示例 → 选择资料 → 运行评测”。每次运行落 `eval_runs`，可传
`baselineKey` + `updateBaseline` 做版本回归对比。

2026-07-18 浏览器运行示例总分为 60.8%：分类 2/2，检索样例因所选资料不包含预期关键点为 0，
回答质量样例通过。该结果是页面 E2E 证据，不是正式 RAG 基线。

---

## 9. 回填 README 的清单

| 评测 | 产物 | README 章节 |
| --- | --- | --- |
| k6 压测 | k6 summary（P95/P99/QPS/TTFT） | 新增「性能压测」表（QPS、P99、TTFT、限流表现） |
| RAG 检索 | `eval/.work/rag-retrieval-report.md` | 「RAG 检索评测结果」 |
| RAGAS | `eval/ragas/.work/ragas-report-*.md` | 「RAGAS 生成质量基线」 |
| Agent Critic | `eval/.work/critic-badcase-report.md` | 「Agent 评测」 |

> 写进简历/README 的每个数字，都要保留对应报告与一次可复现运行（命令 + 环境），面试被追问时能当场复现。
