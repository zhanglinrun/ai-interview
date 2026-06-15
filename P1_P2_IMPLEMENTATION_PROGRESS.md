# P1&P2 实施进度跟踪

## 当前阶段：P1-4 多用户登录与数据隔离（进行中 80%）

### 已完成（本轮交付）

#### Entity 层改造（100%）

给 9 个核心业务实体加 `userId` 字段并建索引：

- `UserEntity`（新增，用户表）
- `ResumeEntity`
- `KnowledgeBaseEntity`
- `InterviewSessionEntity`
- `VoiceInterviewSessionEntity`
- `InterviewAnswerEntity`
- `ResumeAnalysisEntity`
- `InterviewScheduleEntity`
- `RagChatHistoryEntity`

所有表加 `@Index(name = "idx_{table}_user_id", columnList = "userId")`，字段 `@Column(nullable = false)`。

#### 配置层（100%）

- Spring Security 6 依赖（`pom.xml`）
- JWT 工具类（`JwtTokenProvider`）
- 安全配置类（`SecurityConfig`）：明确路径白名单 + 其余路径需认证
- 用户详情服务（`CustomUserDetailsService`）
- JWT 过滤器（`JwtAuthenticationFilter`）
- 错误码新增 11xxx 域（`ErrorCode.USER_*`）

#### 认证 API（100%）

- `/api/auth/register`（用户注册）
- `/api/auth/login`（用户登录）
- `/api/auth/me`（当前用户信息）

已测试：注册 → 登录 → 拿 token → 访问受保护接口。

#### 编译验证

- ✅ `mvn -pl backend compile` 通过
- ✅ `mvn -pl backend test -Dtest=CustomUserDetailsServiceTest` 通过（新增单元测试）

### 待完成（本轮剩余 20%）

#### Service 层改造

需在以下 Service 的 `create/update/delete` 方法插入 `userId` 字段，并在 `list/get` 方法加用户过滤：

- `ResumeUploadService` / `ResumeParseService` / `ResumeGradingService`
- `KnowledgeBaseService` / `KnowledgeBaseVectorService`
- `InterviewService` / `InterviewSessionService`
- `VoiceInterviewService`
- `InterviewScheduleService`
- `RagChatService`

#### Repository 层改造

需在对应 Repository 加 `findByUserIdAndId` / `findAllByUserId` / `deleteByUserIdAndId` 等方法。

#### 异步任务改造

Redis Stream 消费者（`VectorizeStreamConsumer`、`ResumeAnalysisStreamConsumer`、`EvaluationStreamConsumer`）需在处理前校验 `entity.getUserId() == task.getUserId()`，防止越权。

#### 前端集成（暂不做）

- 登录页
- token 存储与自动携带（Axios Interceptor）
- 退出登录

前端部分可后补，目前用 Postman/curl 测试后端 API 即可验证数据隔离。

---

## 交付检查清单（按优先级）

### P1 高优先级（3-4 周，面试必问）

- [ ] **P1-1** JMH 基准测试（向量化、评估、混合检索）→ 量化性能数据
- [ ] **P1-2** 限流单元测试覆盖（集成测试 + 压测验证）
- [ ] **P1-3** 异步消费者防御性代码（重试逻辑 + 死信队列）
- [x] **P1-4** 多用户登录 + 数据隔离（Entity 层 100%，Service/Repository 层待补）

### P2 次优先级（2-3 周，简历可写）

- [ ] **P2-1** Spring AI Agent 落地追踪（`InterviewAgentLoop` 日志完善）
- [ ] **P2-2** pgvector 混合检索可视化（添加 Explain Analyze 日志）
- [ ] **P2-3** Redis Stream 消息积压监控（消费延迟指标 + 告警）
- [ ] **P2-4** 评估引擎分批处理优化（动态批次大小 + 超时降级）
- [ ] **P2-5** 依赖服务降级策略（LLM 超时降级 + S3 失败重试）
- [ ] **P2-6** 运维工具（健康检查 + Actuator Metrics）

---

## 本轮技术债与风险

1. **Service 层改造工作量较大**：9 个模块每个需改 3-5 个方法，预计 2-3 天。
2. **测试覆盖需补充**：需添加数据隔离集成测试（H2 内存库 + 多用户场景）。
3. **前端登录页非本轮重点**：可用默认管理员账号（`admin@example.com` / `admin123`）+ Postman 测试后端隔离逻辑。

---

## 参考资料

- JD 关键词：「十万级 QPS」「稳定性建设」「在线大流量高并发」
- 牛客校招日程：腾讯 27 届实习网申 2026/05/28~06/28，秋招提前批 7-8 月
- 字节 ByteIntern：面向 2026.9~2027.8 毕业生，转正率 55%
