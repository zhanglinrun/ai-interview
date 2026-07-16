# V1 API 与数据参考

本文只列产品主链路和关键数据契约。运行时完整参数与响应以 Swagger
`/swagger-ui.html`、Controller DTO 和当前代码为准，不把本文当作自动生成的 OpenAPI 替代品。

## 1. 通用约定

- API 前缀为 `/api`，普通业务接口使用 `Authorization: Bearer <access-token>`。
- 响应统一包装为 `Result<T>`；业务失败使用 `BusinessException(ErrorCode.XXX, message)`。
- Controller 只做路由、校验和委托，业务状态在 Service 中推进，Entity 不直接作为新 API 契约。
- 用户数据查询必须使用请求线程已解析出的 dataUserId；模型 Provider 身份不能替代数据权限。
- SSE 是通知通道，不是事实源。断线后先 GET 会话状态，再使用 `afterEventId` 继续订阅。
- 所有外部正文、Prompt、源码、隐藏用例和 API Key 都不进入普通日志。

## 2. 主要 API

### 2.1 登录与 BYOK

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/auth/register` | 注册普通用户 |
| `POST` | `/api/auth/login` | 登录并获取 access / refresh token |
| `POST` | `/api/auth/refresh` | 刷新 access token |
| `GET` | `/api/llm-provider/mine` | 查看自己的模型配置状态，不返回 Key 原文 |
| `PUT` | `/api/llm-provider/mine` | 保存并加密自己的 OpenAI-compatible Provider |
| `POST` | `/api/llm-provider/mine/test` | 执行当前用户模型连通性和能力检测 |
| `DELETE` | `/api/llm-provider/mine` | 删除自己的 BYOK 配置并清理缓存 |

平台级 `/api/llm-provider/**` 配置与能力目录导入属于管理员边界；普通用户不应看到平台 Key。

### 2.2 目标岗位与能力目录

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/job-targets` | 保存一份用户 JD |
| `GET` | `/api/job-targets` | 列出当前用户的目标岗位 |
| `GET` | `/api/job-targets/{id}` | 读取当前用户的指定 JD |
| `POST` | `/api/job-targets/{id}/versions` | 为已有 JD 创建新版本 |
| `POST` | `/api/job-targets/{id}/analyze` | BYOK 解析岗位要求并映射能力原子 |
| `PUT` | `/api/job-targets/{id}/capabilities` | 用户确认能力与权重 |
| `POST` | `/api/job-targets/{id}/freeze` | 冻结本次面试使用的 JD / 模板版本 |
| `DELETE` | `/api/job-targets/{id}` | 删除草稿或按隐私规则脱敏历史来源 |
| `GET` | `/api/capability-catalog/templates` | 查询两套已发布岗位模板 |
| `GET` | `/api/capability-catalog/templates/{jobTrack}` | 查询指定岗位方向模板 |
| `POST` | `/api/capability-catalog/admin/validate` | 管理员校验 classpath 内容 |
| `POST` | `/api/capability-catalog/admin/dry-run` | 管理员查看导入计划 |
| `POST` | `/api/capability-catalog/admin/import` | 管理员幂等导入版本化内容 |

V1 内容文件位于
[`catalog-v1.json`](../backend/src/main/resources/capability-content/catalog-v1.json)：当前包含 2 套模板、
13 个能力原子、13 个题型和 9 条 PLATFORM 审核资料清单。

### 2.3 简历、资料与 RAG

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/resumes/upload` | 上传简历并触发异步分析 |
| `GET` | `/api/resumes` | 列出当前用户简历 |
| `GET` | `/api/resumes/{id}/detail` | 查询简历与分析状态 |
| `DELETE` | `/api/resumes/{id}` | 删除简历、关联会话与来源正文 |
| `POST` | `/api/knowledgebase/upload` | 上传单个资料并创建文档版本 |
| `POST` | `/api/knowledgebase/upload/batch` | 批量上传资料 |
| `GET` | `/api/knowledgebase/{id}` | 查询文档状态 |
| `POST` | `/api/knowledgebase/{id}/split` | 结构化切块并触发提交后向量化 |
| `POST` | `/api/knowledgebase/{id}/revectorize` | 手动重切块 / 重新向量化 |
| `GET` | `/api/knowledgebase/{id}/versions` | 查询文档版本 |
| `GET` | `/api/knowledgebase/{documentId}/versions/{versionId}/parse-task` | 查询 MinerU / Tika 解析状态 |
| `POST` | `/api/knowledgebase/query` | 非流式资料问答 |
| `POST` | `/api/knowledgebase/query/stream` | 流式资料问答 |
| `GET` | `/api/knowledgebase/traces/{traceId}` | 查询当前用户的一次 RAG Trace |
| `DELETE` | `/api/knowledgebase/{id}` | 删除对象、向量、缓存并脱敏历史证据 |

资料学习助手允许用户选择知识库；岗位实战不要求手选一个“总知识库”，而是由准备任务基于 JD 和用户
选择构造 `EvidenceScope`。用户可以关闭个人资料增强，但 JD 与 PLATFORM 仍服务于岗位考核计划。

### 2.4 GitHub 公开仓库证据

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/github/repositories` | 绑定公开仓库并固定 Commit SHA |
| `GET` | `/api/github/repositories` | 列出当前用户绑定 |
| `GET` | `/api/github/repositories/{repositoryId}` | 查看候选文件和同步状态 |
| `POST` | `/api/github/repositories/{repositoryId}/sync` | 按确认清单受限同步 |
| `POST` | `/api/github/repositories/{repositoryId}/evidence-cards` | 按能力原子生成代码证据卡 |
| `DELETE` | `/api/github/repositories/{repositoryId}` | 删除索引与正文，脱敏相关快照 |

同步接口只接受官方 GitHub HTTPS URL，受文件数、字节数、树大小、单文件大小和敏感路径预算约束。
面试中的 MCP 复核没有独立用户 API，它由后端根据已冻结 evidenceId 受控调用。

### 2.5 岗位实战

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/job-interviews/preparations` | 创建异步准备任务 |
| `GET` | `/api/job-interviews/preparations/{runId}` | 轮询准备、依赖与降级状态 |
| `GET` | `/api/job-interviews/sessions/{sessionId}` | 读取会话事实快照 |
| `POST` | `/api/job-interviews/sessions/{sessionId}/start` | 开场并返回第一道题 |
| `POST` | `/api/job-interviews/sessions/{sessionId}/answers` | 提交文字回答 |
| `POST` | `/api/job-interviews/sessions/{sessionId}/clarification` | 提交澄清结果 |
| `PUT` | `/api/job-interviews/sessions/{sessionId}/code` | 保存算法草稿 |
| `GET` | `/api/job-interviews/sessions/{sessionId}/code` | 恢复算法草稿 |
| `POST` | `/api/job-interviews/sessions/{sessionId}/code/submit` | 提交岗位算法代码 |
| `POST` | `/api/job-interviews/sessions/{sessionId}/continue` | 24 小时窗口内续面 |
| `POST` | `/api/job-interviews/sessions/{sessionId}/finish` | 主动结束并固化事实 |
| `POST` | `/api/job-interviews/sessions/{sessionId}/abort` | 放弃场次，不更新画像 |
| `GET` | `/api/job-interviews/sessions/{sessionId}/events?afterEventId={id}` | SSE 订阅增量事件 |

所有写命令都携带唯一 `commandId` 和 `expectedSessionVersion`。重复命令返回同一结果；陈旧版本被拒绝，
避免两个浏览器标签覆盖会话状态。

### 2.6 Hot 100 与 Judge0

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/algorithms/problems` | 按语言 / 标签查询已启用题目 |
| `GET` | `/api/algorithms/problem-versions/{problemVersionId}` | 获取冻结题面和语言模板 |
| `POST` | `/api/algorithms/attempts` | 创建岗位或训练 attempt |
| `GET` | `/api/algorithms/attempts/{attemptId}` | 查询 attempt 与当前状态 |
| `GET/PUT` | `/api/algorithms/attempts/{attemptId}/draft` | 读取 / 保存代码草稿 |
| `POST` | `/api/algorithms/attempts/{attemptId}/run` | 使用公开用例运行 |
| `POST` | `/api/algorithms/attempts/{attemptId}/submissions` | 使用隐藏用例提交 |
| `GET` | `/api/algorithms/submissions/{submissionId}` | 查询客观执行事实 |
| `POST` | `/api/algorithms/submissions/{submissionId}/rejudge` | 对待补判记录显式补判 |

[`hot100-v1.json`](../backend/src/main/resources/algorithm-content/hot100-v1.json) 保存 100 道映射和 20 道
V1 启用题。响应不会返回参考实现或隐藏用例；日志也不能记录完整用户源码。

### 2.7 报告、画像、训练和用量

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/reports/sessions/{sessionId}` | 读取客观事实和异步报告状态 |
| `POST` | `/api/reports/sessions/{sessionId}/generate` | 触发幂等报告生成 |
| `POST` | `/api/reports/sessions/{sessionId}/retry` | 重试允许重试的失败报告 |
| `GET` | `/api/capability-profile` | 查询最近有效证据聚合后的能力画像 |
| `GET` | `/api/training/tasks` | 查询当前用户训练任务 |
| `POST` | `/api/training/tasks` | 按能力创建训练 |
| `POST` | `/api/training/tasks/{taskId}/interactions` | 记录提示、答案、重做等交互 |
| `POST` | `/api/training/tasks/{taskId}/complete` | 完成训练并按权重写证据 |
| `GET` | `/api/llm-usage` | 查询自己的模型、耗时、Token、重试和降级 |

只有 `COMPLETED` 岗位报告能写入正式能力证据。`ABORTED` 场次和带提示练习仍可供个人复盘，但不能
直接把画像提升到稳定或优势。

## 3. 四域数据契约

| 域 | owner 规则 | resource 示例 | 默认可见性 | 删除语义 |
| --- | --- | --- | --- | --- |
| `PLATFORM` | 固定公共 owner，不使用 `null` 绕过校验 | 内容版本、能力原子 | 登录用户可检索审核内容 | 停用内容版本并清索引 |
| `JOB` | 当前 dataUserId | JD id + version | 仅所有者 | 草稿删除；历史来源正文脱敏 |
| `CANDIDATE` | 当前 dataUserId | resume / knowledge-base id + version | 仅所有者显式选择 | 删除 DB、MinIO、ES、缓存和快照正文 |
| `GITHUB` | 当前 dataUserId | owner/repo@fixedSha | 仅绑定用户 | 删除快照正文与链接，保留不可复核标记 |

索引和证据对象的核心字段：

```text
ownerUserId
dataDomain
resourceId
resourceVersion
evidenceId
contentHash
sourceType
sourceLocator
```

`EvidenceRef` 是可追溯引用，`EvidencePacket` 是一次问题的有界候选集合与状态，`EvidenceSnapshot` 是
面试开始前冻结的最小事实副本。证据状态固定为 `SUFFICIENT / WEAK / NONE / CONFLICT`；弱或无证据
不能被模型解释为候选人技术回答错误。

## 4. 数据库结构

当前 fresh `schema.sql` 在临时 MySQL 8 空库验证为 51 张表、54 个外键。项目没有引入 Flyway，旧数据
允许删除；生产首次部署或破坏性重建前必须明确快照边界，不能让新代码与旧 schema 混跑。

### 4.1 身份、简历与资料（15 表）

- 身份与简历：`users`、`resumes`、`resume_analyses`
- 文档与 RAG：`knowledge_bases`、`knowledge_base_version`、`knowledge_base_segment`
- 资料问答：`rag_chat_sessions`、`rag_chat_messages`、`rag_session_knowledge_bases`
- 评测与 Trace：`rag_evaluation_runs`、`eval_runs`、`rag_query_traces`
- 解析与证据：`document_parse_tasks`、`evidence_snapshots`、`evidence_snapshot_refs`

### 4.2 面试运行时（9 表）

- `interview_sessions`、`interview_answers`、`interview_questions`
- `interview_commands`、`interview_session_events`、`interview_code_drafts`
- `agent_run_steps`、`candidate_memory`、`interview_schedule`

旧文字面试与岗位实战共享部分面试事实表，通过场次类型、版本和状态字段区分；删除入口必须先判断
会话类型，不能让旧恢复逻辑误处理新岗位场次。

### 4.3 模型配置与用量（4 表）

- `llm_provider_config`、`llm_global_setting`：平台 Provider 与默认设置
- `user_llm_provider`：用户 BYOK 密文和能力状态
- `llm_usage_records`：模型、耗时、Token、重试、降级，不保存 Key 或完整 Prompt

### 4.4 能力模板、JD 与准备（10 表）

- 内容：`capability_content_imports`、`capability_atom_definitions`、`capability_templates`
- 关联：`template_capabilities`、`evaluation_rubrics`、`question_templates`
- 公共知识：`platform_knowledge_manifest`
- 岗位：`job_descriptions`、`job_capability_mappings`
- 异步准备：`job_interview_preparation_runs`

### 4.5 GitHub（3 表）

- `github_repository_bindings`：owner、repo、默认分支、固定 SHA 和同步状态
- `github_repository_files`：候选清单、允许 / 拒绝决定和文件预算
- `github_code_evidence`：路径、符号、行号、hash 与索引证据 ID

### 4.6 算法（6 表）

- `algorithm_content_imports`、`coding_problems`、`coding_problem_versions`
- `coding_attempts`、`coding_drafts`、`judge_submissions`

### 4.7 报告与训练（4 表）

- `interview_evidence_reports`
- `capability_evidence_history`
- `capability_profiles`
- `training_tasks`

## 5. 权限与删除检查

1. Controller 从 `UserContext` 取得当前用户，Service 把 userId 写进查询条件。
2. 资源读取同时匹配主键与 userId；不能先按主键读 Entity 再在 Controller 做弱校验。
3. ES 查询硬过滤 owner、domain、resource 和 version；公共域使用显式公共 owner。
4. 异步消息显式携带 userId，消费者线程不临时读取请求 ThreadLocal。
5. 删除先处理 MySQL 关系和证据脱敏，外部存储在提交后清理；失败需要幂等补偿。
6. 历史报告可以保留结论，但来源正文删除后必须显示“无法复核”，不能保留可还原片段。
