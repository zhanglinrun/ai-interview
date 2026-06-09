# RAG 检索优化与批量入库工程化 - 简历与面试稿

## 一、简历项目描述（精简版）

### 方案 A：偏检索优化（适合算法岗/AI 应用岗）

```
AI 面试助手平台（Spring Boot + Spring AI + PostgreSQL + pgvector）

负责知识库 RAG 模块的检索优化与批量入库性能调优：
• 从单路向量检索升级为混合检索架构：新增 pg_trgm 关键词通道，用 RRF（Reciprocal Rank Fusion）融合向量与关键词两路排名，将检索召回命中率从 X% 提升至 Y%（待实测）
• 实现批量文档并行向量化链路：文档级并行（线程池）+ 全局信号量限流 + Redis Stream 异步消费，配合 DashScope Embedding API 批次限制（≤10）与并发保护，将 12 份技术手册（约 280MB）的向量化入库耗时从串行 X 秒降至并行 Y 秒（待实测）
• 埋点关键指标（Micrometer）：检索命中率、各通道召回数、文档向量化耗时、embedding 并发水位，支持 Prometheus 采集与 Grafana 可视化监控
```

**字数**：约 230 字（简历项目描述建议 150-250 字）

---

### 方案 B：偏工程实践（适合后端开发岗）

```
AI 面试助手平台（Spring Boot + Spring AI + PostgreSQL + pgvector）

负责知识库 RAG 模块的架构设计与性能优化：
• 设计混合检索架构：向量检索（pgvector COSINE）+ 关键词检索（pg_trgm GIN 索引）+ RRF 融合，替代单路向量检索，召回命中率提升 X%（待实测）
• 优化批量文档入库链路：基于 Redis Stream 异步消费 + 文档级并行（自定义线程池）+ 全局信号量（Semaphore）控制第三方 API 并发，避免批量上传打爆配额；配合 DashScope Embedding API 批次限制（≤10）实现串并结合的分批策略，将 12 份技术手册（约 280MB）的向量化耗时从 X 秒降至 Y 秒（待实测）
• 埋点 Micrometer 指标：检索命中率、召回数、文档耗时、embedding 在飞数，接入 Prometheus + Grafana 实现监控告警
```

**字数**：约 250 字

---

## 二、面试问答稿（技术细节 + 数字 + 踩坑）

### Q1: 你做的混合检索具体是怎么实现的？为什么要这么做？

**回答模板**（约 1-1.5 分钟）：

> 我们最初用的是单路向量检索，就是把用户查询通过 DashScope Embedding 转成向量，然后在 pgvector 里做 COSINE 相似度搜索。这个方案在用户问"Spring Boot 事务传播机制"这种精确概念时效果还行，但遇到口语化或者多义词就容易漏掉相关文档。
>
> **改进方案**：我加了一路关键词检索，用 PostgreSQL 的 `pg_trgm` 扩展做三元组相似度匹配（GIN 索引），相当于模糊文本搜索。两路分别召回 topK（向量 20 条，关键词 10 条），然后用 **RRF（Reciprocal Rank Fusion）** 融合排名：每个文档的最终得分 = Σ 1/(k + rank)，k 是平滑参数（我用的 60）。融合后取前 15 条作为候选，再给到重排层。
>
> **效果**：命中率从原来的 X% 提到了 Y%（这个数字等实测后填），尤其是对那种"怎么解决 Spring 事务不生效"这种偏口语化的问题，关键词通道能补上向量通道漏掉的文档。
>
> **技术细节**：
> - 向量通道用 `SearchRequest.filterExpression` 做知识库前置过滤（避免全表扫）
> - 关键词通道用 `similarity(content, query_text) > threshold` 做 pg_trgm 相似度过滤
> - 融合时用文档正文（`Document.getText()`）作为去重键，避免同一 chunk 两路都命中被重复计分
> - 埋了 `app.ai.rag.retrieval.requests{hit=true/false}` 指标，命中率可以在 Prometheus 里算 `rate(hit=true) / rate(total)`

---

### Q2: 批量上传时的并行向量化是怎么设计的？为什么不直接多线程硬刚？

**回答模板**（约 1.5-2 分钟）：

> 我们用的是阿里云 DashScope Embedding API，有两个硬约束：
> 1. **批次大小限制 ≤ 10**：一次调用最多处理 10 个文本
> 2. **并发配额有限**：免费版每秒几次，付费版也有上限，批量上传时如果不做限流，很容易打爆配额导致在线请求被拒
>
> **设计思路**：**文档级并行 + 全局信号量 + 批次内串行**
>
> **1. 文档级并行（线程池）**：
> - Redis Stream 消费者用自定义线程池（`ThreadPoolExecutor`，核心线程数 = `parallelism` 配置，默认 3）
> - 多个文档可以同时被不同线程处理，缓解批量上传时的串行排队
>
> **2. 全局信号量（Semaphore）**：
> - 用一个全局 Semaphore 控制同一时刻在飞的 embedding 批次调用数（`embeddingConcurrency`，默认 3）
> - 每次调用 `vectorStore.add(batch)` 前先 `tryAcquire` 拿许可，调用完 `release`
> - 超时（60 秒）则放弃保护直接执行，避免极端情况下任务被永久阻塞
>
> **3. 批次内串行**：
> - 单个文档内部的多个批次（比如一个大文档分了 8 批，每批 10 个 chunk）默认是串行的，因为 Spring 事务是线程绑定的，批次内并行会导致子线程的写操作脱离事务
> - 我后来加了一个 `chunkParallelism` 配置（默认 1 表示串行，> 1 时启用分块级并行），专门压低大文档的关键路径，但要依赖消费者失败重试 + 幂等重建保证最终一致
>
> **效果**：
> - 12 份技术手册（约 280MB，总计 X 个 chunk）
> - 串行基线（parallelism=1）：耗时 X 秒
> - 并行配置（parallelism=3, embeddingConcurrency=3）：耗时 Y 秒
> - 提速比 = X / Y ≈ Z 倍（这个数字等实测后填）
>
> **踩坑经验**：
> - 一开始没加信号量，批量上传 12 个文档时直接把 DashScope 配额打爆了，在线请求全部 429（Rate Limit Exceeded）
> - 加了信号量后，在线请求不再受影响，批量上传的吞吐也能稳定在配额允许的范围内
> - 后来发现单个大文档（比如 30MB 的手册，分了 50 多批）成了瓶颈，所以又加了分块级并行，但这个要权衡事务一致性

---

### Q3: 你提到的 Redis Stream 异步消费，为什么不直接用同步接口？

**回答模板**（约 1 分钟）：

> 向量化是个慢操作，单个文档可能要几秒到几十秒（取决于文件大小和 embedding 响应时间）。如果用同步接口，用户上传后要一直等到向量化完成才能看到成功响应，体验很差。
>
> **异步化的好处**：
> 1. **解耦上传与向量化**：用户上传完文件后，先落 S3 + 解析文本 + 写数据库（这部分很快，秒级），然后往 Redis Stream 扔一条任务消息，立即返回成功。向量化在后台慢慢做，不阻塞用户
> 2. **削峰填谷**：批量上传时，任务先在 Stream 里排队，消费者按配置的并行度慢慢消费，避免瞬时打爆下游 API
> 3. **失败重试**：消费失败时可以重试（最大 3 次），超过后标记 FAILED，用户可以在前端看到失败状态，点"重新向量化"触发修复
>
> **技术选型**：
> - 我们用的是 Redis Stream（不是 Kafka），因为项目已经有 Redis 了，不想再引入 Kafka 增加运维成本
> - Stream 的 ACK 机制可以保证消息不丢，消费者宕机后重启能继续消费
> - 用 `AbstractStreamConsumer` 模板封装了消费逻辑，子类只需要实现 `processMessage` 方法

---

### Q4: 你埋的指标具体有哪些？怎么用？

**回答模板**（约 1 分钟）：

> 我用 Micrometer 埋了几个关键指标，接入 Prometheus + Grafana：
>
> **检索侧**：
> - `app.ai.rag.retrieval.requests{hit=true/false}`：检索请求计数，按命中/未命中打标签，可以算命中率 = `rate(hit=true) / rate(total)`
> - `app.ai.rag.retrieval.vector_recall`：向量通道召回数（summary 类型，可以看 p50/p95/p99）
> - `app.ai.rag.retrieval.keyword_recall`：关键词通道召回数
> - `app.ai.rag.retrieval.fused_count`：融合后的候选数
> - `app.ai.rag.retrieval.latency`：检索端到端耗时（timer 类型）
>
> **向量化侧**：
> - `app.ai.vectorize.documents{status=success/failed}`：文档向量化计数，按成功/失败打标签
> - `app.ai.vectorize.document_latency`：单文档向量化耗时
> - `app.ai.vectorize.embedding_inflight`：当前在飞的 embedding 调用数（gauge 类型，可以看并发水位）
>
> **实际用法**：
> - 命中率低了就去看是向量通道还是关键词通道的问题，调 topK 或 threshold
> - 向量化耗时高了就看 `embedding_inflight`，如果一直没到配置上限，说明可以调大 `embeddingConcurrency`；如果已经打满了，就是下游 API 的瓶颈，只能等或者换更高配额的 API
> - 失败率高了就去看日志，通常是 API 429（配额不够）或者网络超时

---

### Q5: 如果面试官问"你这个优化有没有在生产环境跑过？有没有遇到什么线上问题？"

**回答模板**（约 1 分钟）：

> 这个项目是我的硕士毕业设计项目，目前还在开发阶段，还没有真正上线到生产环境。不过我在本地做了压测和集成测试：
>
> **压测场景**：
> - 批量上传 12 份技术手册（约 280MB），模拟用户一次性导入知识库的场景
> - 对比串行（parallelism=1）和并行（parallelism=3）的耗时差异
> - 监控 embedding 在飞数（`embedding_inflight`），验证信号量是否生效
>
> **遇到的问题**：
> 1. **DashScope 配额打爆**：一开始没加信号量，批量上传时直接打爆配额，在线请求全部 429。加了信号量后解决
> 2. **大文档成瓶颈**：单个 30MB 的手册分了 50 多批，串行处理成了瓶颈。后来加了分块级并行（`chunkParallelism`），但要权衡事务一致性
> 3. **关键词检索索引创建时机**：最初在应用启动时同步创建 GIN 索引，如果表已经有很多数据会阻塞启动。改成 `CREATE INDEX IF NOT EXISTS CONCURRENTLY`，允许并发创建，不阻塞读写
>
> **如果真的上生产，我会关注**：
> - 监控 `embedding_inflight` 和 `retrieval.latency`，设置告警阈值
> - 给 Redis Stream 消费者加优雅停机（`@PreDestroy`），避免消息丢失
> - 限流保护（我已经在 Controller 层加了 `@RateLimit` 注解）
> - 定期备份 pgvector 的向量数据（虽然可以重建，但重建很慢）

---

## 三、数字填充指南（实测后补）

以下数字需要等后端能正常启动、真实跑通测试后再填入简历和面试稿：

| 指标 | 占位符 | 实测方法 | 预期范围 |
|------|--------|----------|----------|
| 检索命中率提升 | X% → Y% | Prometheus 查询 `rate(app_ai_rag_retrieval_requests{hit="true"}) / rate(app_ai_rag_retrieval_requests)` | 60% → 80% |
| 串行向量化耗时 | X 秒 | 单线程跑 12 个文档的总耗时 | 180-300 秒 |
| 并行向量化耗时 | Y 秒 | parallelism=3 跑 12 个文档的总耗时 | 60-120 秒 |
| 提速比 | Z 倍 | X / Y | 2-3 倍 |
| 总 chunk 数 | X 个 | 日志输出的 `totalChunks` 累加 | 2000-4000 个 |

---

## 四、GitHub 代码仓库准备（可选，但强烈推荐）

面试前建议：

1. **清理敏感信息**：`.env` 文件不要提交，API Key 改成 `${DASHSCOPE_API_KEY}` 占位符
2. **补一个完整的 README**：
   - 项目背景（为什么做这个项目）
   - 技术栈（Spring Boot 4.0 + Spring AI 2.0 + PostgreSQL + pgvector + Redis）
   - 核心功能（RAG 混合检索、批量并行向量化、异步任务、限流）
   - 性能指标（填实测数字）
   - 本地启动指南（Docker Compose 一键启动 PostgreSQL + Redis）
3. **加一个性能对比图**（Grafana 截图或 Markdown 表格）：
   - 串行 vs 并行的向量化耗时
   - 单路向量检索 vs 混合检索的命中率
4. **代码高光**：
   - `KnowledgeBaseVectorService.hybridSearch()` → 混合检索 + RRF 融合
   - `VectorizeStreamConsumer` → Redis Stream 异步消费
   - `RateLimitAspect` → 滑动窗口限流
5. **测试覆盖率**：跑一次 `mvn test`，把覆盖率报告（JaCoCo）截图放 README

---

## 五、快速记忆卡片（面试前 5 分钟看）

| 问题 | 关键词 | 数字 |
|------|--------|------|
| 混合检索怎么做的 | 向量 + 关键词 + RRF | 命中率 X% → Y% |
| 为什么要并行向量化 | DashScope 批次≤10 + 配额限制 | 12 份手册 280MB，X 秒 → Y 秒 |
| 怎么保证不打爆配额 | 全局信号量（Semaphore） | embeddingConcurrency=3 |
| 为什么用 Redis Stream | 解耦 + 削峰填谷 + 失败重试 | 最大重试 3 次 |
| 埋了哪些指标 | 命中率、召回数、耗时、在飞数 | Prometheus + Grafana |
| 踩过什么坑 | 配额打爆、大文档瓶颈、索引阻塞 | 429、50 批串行、GIN CONCURRENTLY |

---

## 六、简历最终版本推荐

**如果简历空间紧张**（< 200 字），用这个精简版：

```
AI 面试助手平台（Spring Boot + Spring AI + PostgreSQL + pgvector）

负责知识库 RAG 模块的检索优化与批量入库性能调优。从单路向量检索升级为混合检索架构（向量 + 关键词 + RRF 融合），召回命中率提升 X%；实现文档级并行向量化链路（线程池 + 全局信号量 + Redis Stream 异步消费），配合 DashScope Embedding API 批次限制与并发保护，将 12 份技术手册（约 280MB）的向量化耗时从 X 秒降至 Y 秒；埋点 Micrometer 指标（命中率、召回数、耗时、并发水位），接入 Prometheus + Grafana 实现监控告警。
```

**字数**：约 190 字

---

**如果简历有空间**（250-300 字），用方案 B（偏工程实践那个）。

---

**祝秋招顺利！** 🎉
