# 故障、降级与恢复矩阵

本项目的可靠性目标不是“外部服务永不失败”，而是失败时不越权、不伪造事实、不覆盖成功终态，并让
用户看到明确状态。下表区分代码 / 自动化证据与尚未完成的真实环境演练。

## 1. 运行时故障矩阵

| 故障 | 可见状态或错误 | 系统行为 | 恢复动作 | 自动化证据 |
| --- | --- | --- | --- | --- |
| 用户未配置 BYOK | `USER_LLM_NOT_CONFIGURED`（7006） | 阻止需要用户模型的生成，引导到“我的模型”；不回退平台 Key 代付 | 用户保存并测试自己的 Provider 后重试 | `LlmProviderConfigServiceTest`、用户 Provider 测试 |
| BYOK 401 / 429 / timeout | 用量记录标记失败、重试或降级 | 有界重试；结构化输出失败的维度保持待评估，不猜测结果 | 检查 Key、额度、模型能力；只重试未完成步骤 | `LlmUsage*Test`、岗位评估服务测试 |
| BYOK 不返回 usage | Token 字段为空或不可用 | 保留耗时和调用状态，不伪造 Token / 费用 | 更换支持 usage 的 Provider，历史记录保持未知 | 代码审查已确认；真实 Provider 契约待 E2E 验收 |
| MinerU 401 / 429 / 5xx / timeout | 解析任务记录失败码与 `fallbackUsed` | 外部调用在事务外；尝试 Apache Tika，明确区分 `SUCCEEDED` 与 `FALLBACK_SUCCEEDED` | 修复 Token / 网络后新建版本或由补偿任务收敛陈旧任务 | `OfficialMineruClientTest`、`MineruProcessServiceTest` |
| MinerU 恶意或超大 ZIP | 解析任务失败，不提取正文 | 拒绝 Zip Slip、压缩炸弹、超大 entry / markdown；不写入对象外路径 | 更换可信文件或服务结果；不自动放宽安全上限 | `MineruZipExtractorTest` |
| MinerU 无法访问 MinIO 签名 URL | 解析失败并记录降级原因 | 桶继续私有，不改成匿名读来“修复” | 检查 files 域名、HTTPS、Caddy GET/HEAD 白名单和 TTL | Mock 已覆盖状态；真实公网访问待验收 |
| Embedding 失败 | 文档停在待向量化状态 | MySQL 已提交事实不回滚；after-commit 事件可重放，补偿任务幂等处理 | 修复平台 Provider 后手动重新向量化或等待补偿 | `DocumentProcessServiceImplTest`、文档补偿测试 |
| Elasticsearch 写入失败 | segment / version 未推进到最终向量状态 | 清理部分写入并保持可补偿状态，避免 DB 假成功 | ES 恢复后重试向量化 | 文档与向量服务单测；真实容器重启待演练 |
| Rerank 超时 / 失败 | EvidencePacket 包含降级原因 | 保留 RRF 候选顺序，不能跨域补数据 | 观察云 Rerank；必要时关闭重排 | `EvidenceRetrievalServiceTest` |
| Decomposition / CRAG 失败 | `WEAK` 或降级原因 | 回退原查询和已有候选；CRAG 最多纠正重检索一次 | 重试下一次准备，不在实时追问循环调用 | `EvidenceRetrievalServiceTest`、CRAG 测试 |
| RAG 无证据 | `NONE` 或 `WEAK` | 明确回答证据不足；候选人事实不因无证据扣技术分 | 用户补充资料，或仅按 Rubric 评价技术回答 | 证据检索与岗位评估测试 |
| RAG 证据冲突 | `CONFLICT` | 报告标记待复核，下一场安排验证题；不做“造假”判定 | 保留各来源引用，用户复核后再生成新证据 | 证据包 / 报告规则测试 |
| GitHub URL 非官方 / SSRF | `GITHUB_INVALID_REPOSITORY_URL` | 在请求外部 API 前拒绝非 HTTPS、非 `github.com` 或非法 owner/repo | 使用公开官方仓库 URL | `GithubRepositoryUrlPolicyTest` |
| GitHub 限流 / 5xx | `GITHUB_RATE_LIMITED` / `GITHUB_API_UNAVAILABLE` | 同步保持失败或部分状态；可选 GitHub 失败不阻塞 JD + PLATFORM 面试 | 等待限流恢复或配置平台公共只读 Token 后重试 | `RestGithubPublicApiClientTest`、`GithubRepositorySyncServiceTest` |
| GitHub 仓库过大 / 敏感文件 | `GITHUB_SYNC_LIMIT_EXCEEDED` 或文件拒绝原因 | 在文件数、树、字节和单文件预算内选择性同步；二进制、依赖、产物、密钥不入索引 | 缩小确认清单，不提高到无界预算 | `GithubFilePolicyTest`、`GithubRepositorySyncServiceTest` |
| GitHub Force Push / hash 不一致 | 来源不可用或证据读取失败 | 固定 SHA 内容 hash 必须匹配；不把新 HEAD 偷换进历史场次 | 重新绑定新版本；历史报告保留旧 SHA 元数据 | GitHub API / evidence reader tests |
| GitHub MCP 关闭 / 超时 / 越界工具 | 快照回退，复核状态降级 | 只读白名单拒绝越界 owner/repo/SHA/path/tool；MCP 不可用时读取固定快照 | 检查 MCP Token / endpoint；不扩大权限 | `GithubMcpWhitelistTest`、`OfficialGithubRemoteMcpClientTest`、`GithubEvidenceReaderTest` |
| Judge0 未配置 | `UNAVAILABLE`、`pendingRejudge=true` | 保存代码和过程评价，不生成 AC / WA 等执行结论 | 配置实例和 language id 后手动补判 | `Judge0JudgeClientTest`、`CodingJudgeServiceTest` |
| Judge0 编译错 / WA / TLE / MLE | 对应 `JudgeStatus` | 客观事实原样保存；LLM 只能评价思路和代码质量，不能覆盖状态 | 用户修改代码后新提交；同一幂等键不重复执行 | Judge0 client、harness、submission tests |
| Judge0 429 / timeout / 5xx | `UNAVAILABLE` 或 `INTERNAL_ERROR`，带失败码 | 不阻塞整场面试；允许显式 rejudge，不重复 LLM 过程评价 | 服务恢复后补判 | `Judge0JudgeClientTest`、`CodingSubmissionPersistenceServiceTest` |
| 重复 / 并发面试命令 | 返回已保存结果或拒绝陈旧版本 | `commandId` 幂等 + `expectedSessionVersion` 乐观锁 + 单会话串行 | 客户端刷新会话快照后再提交 | `JobInterviewCommandPersistenceServiceTest`、`JobInterviewRuntimeServiceTest` |
| 客户端把同一 `commandId` 用于不同 payload | 返回该 key 首次保存的命令结果 | V1 只按用户 / 场次 / key 去重，未持久化 payload fingerprint；不会跨用户污染，但无法识别同一用户的错误复用 | 每个不同动作生成新 UUID；后续版本可增加请求 fingerprint 冲突校验 | 已知 V1 边界；现有幂等与用户隔离测试 |
| SSE 断线 | 浏览器连接结束，MySQL 状态不丢失 | 生成和持久化继续；不依赖逐 Token 回放 | GET 会话快照，再携带 `afterEventId` 重连 | `JobInterviewEventStreamServiceTest` |
| 会话长时间无活动 | `IN_PROGRESS -> PAUSED -> ABORTED` | 生命周期任务给出 24 小时恢复窗口；过期场次不更新画像 | 窗口内 `continue`，过期后新建场次 | `JobInterviewLifecycleServiceTest` |
| RabbitMQ 暂时不可用 | 准备 / 报告保持可重试状态 | 生产端不伪造成功；消费者以业务 ID 和终态检查保证幂等，DLQ 记录告警 | MQ 恢复后重投未完成任务，不能覆盖成功终态 | Rabbit consumer / stream fallback tests；真实重启待演练 |
| 重复消费或实体已删除 | 已完成状态不变，删除实体不重建 | 消费前检查实体、attempt 与终态；重复消息安全返回 | 无需人工修复，检查异常 DLQ | `EvaluateStreamConsumerTest`、准备 / 报告处理测试 |
| 报告结构化生成失败 | `FAILED` 或部分维度待评估 | 原始题目、回答和 Judge0 事实仍可见；自动重试最多一次，支持手动 retry | 只重试失败步骤，不重复扣费或覆盖已完成内容 | `ReportGenerationProcessorTest`、`ReportFactAssemblerTest` |
| 报告 / 训练重复请求 | 返回已有结果 | 只有 `COMPLETED` 报告原子写画像；带提示训练按低权重处理 | 查询当前状态，不创建重复证据 | `CapabilityProfileAggregatorTest`、报告处理测试 |
| Redis 丢失 / 重启 | 缓存、短期会话记忆丢失 | 不丢 MySQL 业务事实；状态快照仍可加载 | Redis 恢复后重新建立缓存 / 记忆 | 设计与单测存在；真实容器重启待演练 |
| 用户删除资料 / 仓库 / 会话 | 来源正文变为不可复核或实体消失 | 事务内先清关系与脱敏快照，提交后删 MinIO / ES；不会误删其他域证据 | 外部删除失败时补偿；历史报告只保留不可还原元数据 | `EvidenceSnapshotServiceTest`、各删除测试 |

## 2. 面试和异步状态

### 2.1 MinerU 解析

```text
CREATED -> SUBMITTED -> POLLING -> SUCCEEDED
                              \-> FAILED -> FALLBACK_SUCCEEDED
                                          \-> FALLBACK_FAILED
```

只有 `SUCCEEDED` 表示官方 MinerU 精准解析成功；`FALLBACK_SUCCEEDED` 必须在 UI、指标和验收报告中
明确显示 Tika 降级。

### 2.2 岗位准备与场次

```text
Preparation: DRAFT -> PREPARING -> READY | FAILED
Session:     READY -> IN_PROGRESS -> PAUSED -> IN_PROGRESS
               \-> ABORTED   |       \-> COMPLETED | ABORTED
                              \-> COMPLETED | ABORTED
```

JD、模板、计划和 Rubric 未就绪时准备不能进入 `READY`。简历、个人资料、GitHub 和 Judge0 属于可选依赖，
失败会写入降级原因，但不必阻断整场。失败的准备任务通过新建 preparation run 重试，不原地改回
`PREPARING`。当前岗位实战命令链也不会推进 session 枚举中预留的 `COMPLETING` / `FAILED`：交卷直接
完成 session，报告状态由独立异步链路维护。

### 2.3 Judge0 与报告

```text
Judge: QUEUED -> PROCESSING -> ACCEPTED | WRONG_ANSWER | COMPILE_ERROR
                            | RUNTIME_ERROR | TIME_LIMIT_EXCEEDED
                            | MEMORY_LIMIT_EXCEEDED | INTERNAL_ERROR | UNAVAILABLE

Report: GENERATING -> COMPLETED | FAILED
```

`UNAVAILABLE` / `INTERNAL_ERROR` 可以补判；代码错误状态需要新提交。只有 `COMPLETED` 报告参与能力聚合。

## 3. 可观测性与取证

- HTTP 请求由 `TraceContext` / MDC 关联 traceId；不得把 Authorization 或签名 URL写进 MDC。
- `RagQueryTrace` 保存改写、子查询、候选、重排、最终证据和耗时，不保存用户 Key。
- Agent Trace 保存计划、动作和受限 Reflection；不能把其当成业务事实表。
- `llm_usage_records` 保存用户可见的模型、耗时、Token、状态、重试与降级，不保存完整 Prompt。
- Prometheus / Grafana 观测 JVM、HTTP、RAG、LLM、MQ、解析、Judge0 和异步任务。
- 可选 Logstash / Kibana 复用业务 Elasticsearch，但使用独立 `ai-interview-logs-*` 索引和 7 天生命周期。

## 4. 已验证与待验证边界（2026-07-15）

已执行的本地门禁：

- 后端 `mvn clean test` 共 346 tests，0 failures，0 errors，3 skipped；跳过项需要真实 Redis
  环境。
- 前端全量 Vitest、TypeScript 和生产构建通过。
- 20 道启用题的 Java 21 / Python 3 参考实现通过本地编译器 / 解释器与全部仓库用例。
- 所有 Compose 拓扑和 4C6G 静态资源 / 端口策略通过配置校验。
- Fresh MySQL 8 初始化 51 张表、54 个外键。
- 隔离项目名和空卷启动 `docker-compose-ip.yml` 的 7 个核心容器，全部进入 healthy 且零重启；
  注册、登录、匿名鉴权、能力模板、20 道 Java 题、Judge0 关闭降级和 JD CRUD 冒烟通过。

仍未完成，不能写成实测成果：

- MinerU 对 PDF / DOCX / HTML 的真实官方 API 验收和外部 files 域名访问。
- Judge0 真实实例的正常、限流、超时和补判联调。
- GitHub 真实公共仓库完整报告，以及只读 MCP 确实命中后的脱敏证据。
- 一场真实 45 分钟四阶段浏览器面试、断线恢复和 24 小时续面。
- Redis、RabbitMQ、ES、MinIO 单容器重启与 MySQL 恢复演练。
- 4C6G 目标服务器不少于 24 小时的 CPU、内存、swap、磁盘和日志观察。

部署与演练步骤见 [`../dev-ops/DEPLOY.md`](../dev-ops/DEPLOY.md)。
