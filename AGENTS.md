# AI Interview Platform 编码规范

Spring Boot 4.0 + Java 21 + LangChain4j + Elasticsearch + React 面试平台。写代码时遵守以下规则。

---

## 一、项目结构

Maven 多模块项目，后端位于 `backend` 模块，按功能分包：

```
interview.guide/
├── App.java                          # @SpringBootApplication + @EnableScheduling
│
├── common/                           # 通用基础能力
│   ├── annotation/                   #   @RateLimit（可重复注解，滑动窗口限流）
│   ├── aspect/                       #   RateLimitAspect（AOP + Redis Lua 限流）
│   ├── ai/                           #   StructuredOutputInvoker（结构化输出重试）
│   │                                 #   LlmProviderRegistry（多 LLM Provider 注册与缓存）
│   │                                 #   PromptSanitizer（Prompt 注入防御）
│   │                                 #   ApiPathResolver（多 Provider API 路径适配）
│   ├── async/                        #   AbstractStreamConsumer/Producer（Redis Stream 模板）
│   ├── config/                       #   配置类（CORS、S3、ObjectMapper、OpenAPI、LlmEmbedding）
│   ├── constant/                     #   CommonConstants、AsyncTaskStreamConstants
│   ├── evaluation/                   #   UnifiedEvaluationService（统一评估引擎）
│   │                                 #   QaRecord、EvaluationReport（文字/语音共用）
│   ├── exception/                    #   ErrorCode（10 个错误域 1xxx-10xxx）
│   │                                 #   BusinessException、GlobalExceptionHandler
│   ├── model/                        #   AsyncTaskStatus
│   └── result/                       #   Result<T>（统一响应包装）
│
├── infrastructure/                   # 技术基础设施
│   ├── export/                       #   PdfExportService（iText 8）
│   ├── file/                         #   文件解析（Tika）、存储（S3/RustFS）、校验、清洗
│   ├── mapper/                       #   MapStruct 映射器（Interview、Resume、KB、RagChat）
│   └── redis/                        #   RedisService、InterviewSessionCache
│
└── modules/                          # 业务模块（每个模块自包含 MVC 分层）
    ├── resume/                       #   简历管理：上传、解析、AI 评分、去重、历史记录
    ├── interview/                    #   模拟面试：会话管理、Skill 出题、追问、评估
    │   ├── agent/                    #   ReAct Agent：自适应出题（知识库检索、简历读取）
    │   ├── skill/                    #   Skill 管理：10+ 方向、JD 解析、分类匹配
    │   └── listener/                 #   异步评估（Redis Stream 消费者）
    ├── knowledgebase/                #   知识库：三表（文档/版本/分段）+ 版本管理 + Spring 事件向量化 + RAG 查询
    │   ├── constant/                 #   DocumentStatus/SegmentStatus/SplitType/FileType 状态机
    │   ├── config/                   #   ElasticSearchConfiguration、MineruProperties
    │   ├── event/                    #   DocumentChunkedEvent + DocumentEventListener（@Async+AFTER_COMMIT）
    │   ├── job/                      #   DocumentCompensationJob（@Scheduled 向量化补偿 + 旧版本清理）
    │   ├── model/                    #   KnowledgeBaseEntity + VersionEntity + SegmentEntity（三表）
    │   ├── rag/                      #   ContentRetriever/Aggregator/QueryTransformer/QueryRouter（对齐 know-engine）
    │   ├── repository/               #   KnowledgeBase + Version + Segment Repository
    │   ├── service/                  #   DocumentProcessService 编排、版本管理、分段、VectorStore、RAG 查询、Rerank
    │   │   └── parse/                #   FileProcessService 工厂 + MineruProcessService + MarkdownProcessService
    │   └── service/splitter/         #   MarkdownHeaderParent/BrotherTextSplitter（对齐 know-engine）
    ├── interviewschedule/            #   面试安排：日历管理、AI 解析面试邀请、提醒
    ├── voiceinterview/               #   语音面试：WebSocket 实时通话、Qwen3 ASR/TTS
    │   ├── handler/                  #   WebSocket 处理器（实时字幕、VAD 断句）
    │   └── service/                  #   语音服务（流式 TTS、并发合成）
    └── llmprovider/                  #   多模型管理：Provider 配置、默认模型切换
        └── service/                  #   API Key 加密、连通性测试、启动加载
```

**技术栈**：Spring Boot 4.0.1 / Java 21（虚拟线程）/ LangChain4j 1.11.0（替代 Spring AI，对齐 know-engine）/ JPA + PostgreSQL + Flyway / Elasticsearch（向量存储，替代 pgvector）/ Redisson 3.50.0 / Redis Stream（简历/面试评估）+ Spring 事件（知识库向量化）/ MapStruct 1.6.3 / iText 8.0.5 / Apache Tika 2.9.2 + MinerU（文档解析，Tika fallback）/ DashScope SDK 2.22.7（ASR/TTS）

**前端**：React 18.3 + TypeScript 5.6 + Vite 5.4 + Tailwind CSS 4.1 + React Router 7.11 + Framer Motion 12.23（`frontend/` 目录）

---

## 二、分层架构

```
Controller → Service → Repository
                ↕
          Infrastructure（RedisService、FileStorageService、PdfExportService）
```

### Controller 层

- 仅路由和委托，禁止业务逻辑
- RESTful 风格：`/api/{module}/{action}`
- 使用 `@RateLimit` 注解做限流（`@Repeatable`，每维度独立 count）
- 通过 `@Valid` + `@RequestBody` 校验请求

### Service 层

- 业务逻辑编排，合理拆分大 Service（如 `ResumeUploadService`、`ResumeParseService`、`ResumeGradingService`）
- 使用 `LlmProviderRegistry.getChatModelOrDefault(provider)` 获取 LangChain4j `ChatModel`（流式用 `getStreamingChatModelOrDefault`，嵌入用 `getDefaultEmbeddingModel`，支持多 Provider）
- 异步任务：简历分析/面试评估用 Redis Stream（`AbstractStreamProducer/Consumer` 模板），知识库向量化用 Spring 事件（`DocumentChunkedEvent` + `@Async`）
- 所有业务异常使用 `BusinessException(ErrorCode.XXX, message)`，禁止 `RuntimeException`

### Repository 层

- Spring Data JPA，继承 `JpaRepository`
- 自定义查询用 `@Query` 或方法命名约定

---

## 三、JavaBean 后缀规则

| 后缀 | 用途 | 示例 |
|------|------|------|
| `XxxEntity` | JPA 持久化 | `ResumeEntity`、`InterviewSessionEntity` |
| `XxxDTO` | 跨层数据传输 | `ResumeListItemDTO`、`SessionResponseDTO` |
| `XxxRequest` | 前端请求体 | `CreateInterviewRequest`、`QueryRequest` |
| `XxxResponse` | 前端响应体 | `QueryResponse`、`SubmitAnswerResponse` |

- 不可变数据载体优先用 `record`（如 `CreateInterviewRequest`、`QueryRequest`）
- Entity 映射用 MapStruct（`@Mapper(componentModel = "spring")`）
- 简单场景可用 `BeanUtils.copyProperties`
- **禁止直接返回 Entity 给前端**

---

## 四、异常与错误码

### ErrorCode 分域规则

| 域 | 范围 | 示例 |
|----|------|------|
| 通用 | 1xxx | BAD_REQUEST(400)、NOT_FOUND(404) |
| 简历 | 2xxx | RESUME_NOT_FOUND(2001) |
| 面试 | 3xxx | INTERVIEW_SESSION_NOT_FOUND(3001) |
| 存储 | 4xxx | STORAGE_UPLOAD_FAILED(4001) |
| 导出 | 5xxx | EXPORT_PDF_FAILED(5001) |
| 知识库 | 6xxx | KNOWLEDGE_BASE_NOT_FOUND(6001) |
| AI 服务 | 7xxx | AI_SERVICE_TIMEOUT(7002) |
| 限流 | 8xxx | RATE_LIMIT_EXCEEDED(8001) |
| 面试日程 | 9xxx | INTERVIEW_SCHEDULE_NOT_FOUND(9001) |
| 语音面试 | 10xxx | VOICE_SESSION_NOT_FOUND(10001) |

### 异常处理规则

- 抛出：`throw new BusinessException(ErrorCode.XXX, "描述信息")`
- **禁止** `throw new RuntimeException(...)` —— 必须用 `BusinessException`
- 全局异常处理器 `GlobalExceptionHandler` 统一返回 HTTP 200 + `Result.error(code, message)`
- `catch (BusinessException e) { throw e; }` 保留业务异常原样抛出

---

## 五、限流组件

```java
// 每个 @RateLimit 对应一个维度，各自独立的 count/interval/timeUnit
@RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
@RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
public Result<QueryResponse> queryKnowledgeBase(...) { ... }
```

- 注解：`@Repeatable`，AOP 切面 `RateLimitAspect` 逐条执行单 key Lua 脚本
- Lua 脚本：`resources/scripts/rate_limit_single.lua`，滑动时间窗口
- Redis Key 设计：`ratelimit:{ClassName:MethodName}:dimension`（Hash Tag 分组）
- 维度：`GLOBAL`（全局限流）、`IP`（按 IP）、`USER`（按用户）

---

## 六、异步任务

项目有两种异步机制：

### Redis Stream（简历分析、面试评估）

使用 `AbstractStreamProducer` / `AbstractStreamConsumer` 模板：

```java
// 生产者
public class ResumeAnalyzeStreamProducer extends AbstractStreamProducer<ResumeAnalyzeTask> { ... }

// 消费者
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluatePayload> { ... }
```

- 两条管道：简历分析、面试评估（知识库向量化已迁至 Spring 事件，见下）
- 常量定义在 `AsyncTaskStreamConstants`
- 消费者实现 `processMessage()` 方法
- **失败重试**：最大 3 次，超过后标记 FAILED
- **实体删除**：异步处理前校验实体是否存在，不存在直接 ACK 丢弃

### Spring 事件（知识库向量化，对齐 know-engine）

```java
// 切块完成后发布事件
eventPublisher.publishEvent(new DocumentChunkedEvent(docId, versionId, segmentCount));

// 监听器 @Async + AFTER_COMMIT 异步触发向量化
@Async("eventListenerExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onDocumentChunked(DocumentChunkedEvent event) { ... }
```

- `DocumentChunkedEvent` + `DocumentEventListener`（`@Async` + `AFTER_COMMIT`，保证切块事务提交后才向量化）
- 线程池 `eventListenerExecutor`（`AsyncConfig`，ThreadPoolTaskExecutor 核心4/最大8/队列50/CallerRunsPolicy）
- **无显式 FAILED**：失败靠版本停在 `CHUNKED`，由 `DocumentCompensationJob`（`@Scheduled`）兜底重试
- **补偿任务**：`@Scheduled` 替代 XXL-Job（向量化补偿扫 CHUNKED 重试 + 旧版本清理扫残留 segment）

---

## 七、AI 服务调用

### LLM Provider（LangChain4j，对齐 know-engine）

```java
// 统一通过 Registry 获取 ChatModel / StreamingChatModel / EmbeddingModel，支持多 Provider 路由
ChatModel chatModel = llmProviderRegistry.getChatModelOrDefault(provider);
StreamingChatModel streamingChatModel = llmProviderRegistry.getStreamingChatModelOrDefault(provider);
EmbeddingModel embeddingModel = llmProviderRegistry.getDefaultEmbeddingModel();
```

- 已从 Spring AI `ChatClient` 迁移到 LangChain4j `ChatModel` / `StreamingChatModel` / `EmbeddingModel`
- 配置：`app.ai.providers.{providerId}.baseUrl/apiKey/model`，默认 Provider `app.ai.default-provider`
- 敏感词过滤：`SafeGuardChatModel` / `SafeGuardStreamingChatModel` 包装底层模型
- ReAct Agent：用 LangChain4j `AiServices` + `@Tool` 方法（`InterviewAgentLoop`），`ToolListener` 捕获执行轨迹，`ThreadLocal` 传 `AgentToolContext`
- RAG 编排：用 LangChain4j `DefaultRetrievalAugmentor` + `ContentRetriever`/`ContentAggregator`/`QueryTransformer`/`QueryRouter`（对齐 know-engine，见 `modules/knowledgebase/rag/`）

### 结构化输出

```java
// 使用 StructuredOutputInvoker 做重试包装
String result = structuredOutputInvoker.invokeStructuredOutput(prompt, ChatModel, outputConverter);
```

### Prompt 模板

- 存放在 `resources/prompts/`，使用 StringTemplate（`.st`）格式
- 语音面试角色模板：`prompts/voice-interview/*.st`

---

## 八、格式与命名

- **2 空格缩进**，列限制 100 字符
- 类名 UpperCamelCase，方法名 lowerCamelCase，常量 UPPER_SNAKE_CASE
- **禁止通配符导入**
- 优先 `record` 作为不可变数据载体
- 使用现代 Java 特性：`switch` 表达式、pattern matching `instanceof`、text blocks
- 避免内联全限定类名（用 import 代替）

---

## 九、事务规则

- `@Transactional` 放 Service 层
- **禁止**在事务方法内调用外部 API（LLM 调用、S3 上传等）
- **禁止**同类内部调用 `@Transactional` 方法（AOP 代理不生效）
- 保持事务范围最小

---

## 十、日志规范

- 使用 SLF4J（`@Slf4j`）
- 结构化日志：`log.info("Session created: sessionId={}, role={}", id, role)`
- 异常作为最后一个参数：`log.error("Evaluation failed: sessionId={}", id, e)`
- **禁止** `log.error("Error: {}", e.getMessage())`（丢失堆栈）

---

## 十一、数据库与向量存储

- PostgreSQL（关系数据，业务表）+ Flyway（数据库版本迁移）
- Elasticsearch（向量存储，替代 pgvector；LangChain4j `ElasticsearchEmbeddingStore`，1024 维 COSINE，单一索引靠 metadata docId/version 区分）
- 知识库三表结构：`knowledge_bases`（文档主表）+ `knowledge_base_version`（版本表）+ `knowledge_base_segment`（分段表，存 chunk 文本 + embeddingId + 分段级状态机）
- JPA 实体使用 `@Data`、`@Builder`、`@NoArgsConstructor`、`@AllArgsConstructor`
- `ddl-auto` 开发环境 `update`，生产环境 `false`（表结构由 JPA Entity 注解驱动 + Flyway 迁移）

---

## 十二、配置管理

- 配置文件：`application.yml` + `.env`（通过 `spring.config.import`）
- 敏感信息（API Key、数据库密码）放 `.env`，不入版本控制
- 业务配置用 `@ConfigurationProperties`（如 `VoiceInterviewProperties`、`AppConfigProperties`）
- **禁止** `@Value` 散落在 Service 中（集中到 Properties 类）

---

## 十三、测试

- JUnit 5 + Mockito + AssertJ
- `@DisplayName` 中文描述测试意图
- `@Nested` 按功能分组测试
- 集成测试用 H2 内存数据库（`application-test.yml`）
- 限流测试需要真实 Redis

---

## 速查：禁止清单

| 禁止项 | 原因 |
|--------|------|
| `throw new RuntimeException(...)` | 绕过全局异常处理，用 `BusinessException` |
| 直接返回 Entity 给前端 | 暴露内部结构 |
| `@Value` 散落在 Service 中 | 配置应集中到 `@ConfigurationProperties` |
| 内联全限定类名（`org.springframework...`） | 用 import 代替 |
| 事务内调用外部 API（LLM、S3） | 占用 DB 连接 |
| 同类内部调用 `@Transactional` | AOP 代理不生效 |
| `catch (Exception e) {}` 静默忽略 | 隐藏错误 |
| 循环调用 DB | 改用批量操作 |
| 硬编码密钥 | 安全风险 |
| `Executors.newXxxThreadPool()` | OOM 风险，用 `ThreadPoolExecutor` |
