<div align="center">

**智能 AI 面试官平台** - 基于大语言模型的简历分析、模拟面试和 RAG 知识库系统

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18.3-blue?logo=react)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-blue?logo=typescript)](https://www.typescriptlang.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14+-336791?logo=postgresql)](https://www.postgresql.org/)
[![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.x-005571?logo=elasticsearch)](https://www.elastic.co/elasticsearch/)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.11.0-blueviolet?logo=langchain)](https://docs.langchain4j.dev/)


</div>


---

## 项目介绍

InterviewGuide 是一个集成了简历分析、模拟面试（文字 + 语音）、面试安排、知识库管理和多模型配置的智能面试辅助平台。系统利用大语言模型、向量数据库、异步任务队列和实时语音技术，为求职者、HR 和培训机构提供智能化的简历评估、面试练习、知识库问答和日程管理能力。

核心特性：
- **ReAct Agent 自适应出题**：基于知识库检索和简历分析，动态调整面试题目和难度
- **10+ 面试方向 Skill**：Java 后端、阿里/字节/腾讯专项、前端、Python、算法、系统设计等，支持 JD 智能解析
- **统一评估引擎**：文字面试和语音面试使用同一套评估逻辑，结果可对比分析
- **实时语音面试**：WebSocket 双向通信，支持流式对话、自动断句、回声防护和暂停恢复
- **多模型管理**：支持 DashScope、Kimi、DeepSeek、GLM 等多个 LLM Provider，运行时可视化切换
- **RAG 知识库**：LangChain4j `DefaultRetrievalAugmentor` 编排 + 混合检索 + 查询改写 + RRF 融合 + 智能 Rerank（对齐 know-engine），提供专业领域问答能力

## 系统架构

![系统架构图](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/interview-guide-architecture-diagram.png)

## 配套教程

本项目承诺**完整功能免费开源**，也不会做所谓的 Pro 版或“付费解锁核心功能”之类的设计。

如果你想学习这个项目，或者希望把它作为个人项目经历 / 毕设选题，我也整理了一套相对细致的教程：从基础设施搭建、核心业务实现，到最后如何在面试中讲清楚思路与亮点，尽量把容易卡住的地方讲透。

如果你确实需要更系统的辅导，可以点这里了解详情（**教程为付费内容**，主要是想覆盖一些时间成本，望理解，感谢支持）：[《SpringAI 智能面试平台+RAG 知识库》](https://javaguide.cn/zhuanlan/interview-guide.html)。

## 技术栈

### 后端技术

| 技术                  | 版本  | 说明                          |
| --------------------- | ----- | ----------------------------- |
| Spring Boot           | 4.0.1 | 应用框架                      |
| Java                  | 21    | 开发语言（虚拟线程）          |
| LangChain4j           | 1.11.0 | AI 编排框架（替代 Spring AI，对齐 know-engine；OpenAI 兼容模型接入、AiServices、RAG、Agent）|
| PostgreSQL + Flyway   | 14+ / 11.x | 关系数据库 + 版本迁移 |
| Elasticsearch         | 8.x   | 向量存储（替代 pgvector，LangChain4j ElasticsearchEmbeddingStore，1024 维 COSINE）|
| Redis + Redisson      | 6+ / 3.50.0 | 缓存 + 消息队列（Stream，简历/面试评估）|
| Spring 事件 + @Async  | -     | 知识库向量化异步（对齐 know-engine，DocumentChunkedEvent + AFTER_COMMIT）|
| Apache Tika + MinerU  | 2.9.2 / - | 文档解析（MinerU 主路径，Tika fallback）|
| iText 8               | 8.0.5 | PDF 导出                      |
| MapStruct             | 1.6.3 | 对象映射                      |
| SpringDoc OpenAPI     | 2.8.17 | API 接口文档                  |
| DashScope SDK         | 2.22.7 | 语音识别/合成（Qwen3 ASR/TTS）|
| AWS S3 SDK            | 2.29.51 | S3 兼容对象存储（MinIO/RustFS）|
| XXL-Job（可选）       | 3.2.0 | 分布式任务调度（已用 @Scheduled 替代，依赖保留）|
| WebSocket             | -     | 语音面试实时双向通信          |
| Maven                 | 3.8+  | 构建工具                      |

技术选型常见问题解答：

1. AI 编排为什么从 Spring AI 迁移到 LangChain4j？为了对齐企业级 RAG 项目 know-engine 的架构（AiServices、`DefaultRetrievalAugmentor` + `ContentRetriever`/`ContentAggregator` 编排、Elasticsearch 向量存储），LangChain4j 的 RAG 抽象更成熟，便于复刻 know-engine 的混合检索 + Rerank + 多路召回 + RRF 融合能力。
2. 向量存储为什么从 pgvector 换成 Elasticsearch？对齐 know-engine 用 ES 做向量 + 全文 + 混合检索（KNN），ES 的 metadata filter 删除（按 docId/version）和规模化能力更适合知识库版本管理场景。单一索引靠 metadata 的 docId/version 区分文档版本。
3. 为什么引入 Redis？
   - Redis 替代 `ConcurrentHashMap` 实现面试会话的缓存。
   - 基于 Redis Stream 实现简历分析、面试评估的异步（还能解耦，分析和评估可以使用其他编程语言来做）。知识库向量化已迁移到 Spring 事件 + `@Async`（对齐 know-engine，`DocumentChunkedEvent` + `AFTER_COMMIT`）。
4. 构建工具已迁移为 Maven，后端通过根目录父 POM 管理 `backend` 模块，便于使用标准 Maven 命令编译、测试和打包。数据库版本迁移用 Flyway。

### 前端技术

| 技术              | 版本  | 说明           |
| ----------------- | ----- | -------------- |
| React             | 18.3  | UI 框架        |
| TypeScript        | 5.6   | 开发语言       |
| Vite              | 5.4   | 构建工具       |
| Tailwind CSS      | 4.1   | 样式框架       |
| React Router      | 7.11  | 路由管理       |
| Framer Motion     | 12.23 | 动画库         |
| Recharts          | 3.6   | 图表库         |
| Lucide React      | 0.468 | 图标库         |
| React Big Calendar| 1.19  | 面试日历组件   |
| React Virtuoso    | 4.18  | RAG 聊天虚拟列表 |
| pnpm              | 10.26 | 前端包管理器   |

## 功能特性

### 简历管理模块

- **多格式解析**：支持 PDF、DOCX、DOC、TXT 等多种简历格式。
- **异步处理流**：基于 Redis Stream 实现异步简历分析，支持实时查看处理进度（待分析/分析中/已完成/失败）。
- **稳定性保障**：内置分析失败自动重试机制（最多 3 次）与基于内容哈希的重复检测。
- **分析报告导出**：支持将 AI 分析结果一键导出为结构化的 PDF 简历分析报告。

### 模拟面试模块

- **Skill 驱动出题**：内置 10+ 面试方向（Java 后端、阿里/字节/腾讯专项、前端、Python、算法、系统设计、测开、AI Agent 等），每个方向由 `SKILL.md` 定义考察范围、难度分布和参考知识库。
- **历史题目去重**：出题时自动排除已有会话中问过的题目，避免重复考察。
- **面试阶段时长联动**：总时长滑块拖动后，各阶段（自我介绍、技术考察、项目深挖、反问环节）按时比自动分配。
- **智能追问流**：支持配置多轮智能追问（默认 1 条），模拟多轮问答场景。
- **统一评估架构**：文字面试和语音面试共用同一套评估引擎（分批评估 + 结构化输出 + 二次汇总 + 降级兜底），评估结果可对比。
- **报告一键导出**：支持异步生成并导出详细的 PDF 模拟面试评估报告。
- **面试中心入口**：面试中心页整合文字面试和语音面试入口，支持继续面试和重新面试。

### 面试安排模块

- **邀请解析**：规则 + AI 双引擎，支持飞书/腾讯会议/Zoom 格式，自动提取公司、岗位、时间、会议链接
- **日历管理**：日/周/月视图 + 拖拽调整 + 列表视图
- **状态流转**：定时任务自动过期，手动标记待面试/已完成/已取消
- **面试提醒**：可配置提醒，避免错过面试

### 语音面试模块

实时语音对话面试，WebSocket + 千问3 语音模型（ASR/TTS/LLM 统一 API Key）：

- **实时流式对话**：句子级并发 TTS，边生成边合成边播放，首包延迟 200ms
- **服务端 VAD**：自动断句，实时字幕（含中间结果）
- **回声防护 + 手动提交**：避免 AI 语音被误录入
- **多轮上下文记忆 + 暂停/恢复**：超时自动暂停
- **Micrometer 埋点**：TTS/ASR 延迟、会话时长等指标

> **已知问题**：端到端延迟偏高（服务端音频中转）、无耳机时回声泄漏、TTS 音色单一、弱网音频断续。后续计划探索 WebRTC、客户端 VAD 降噪、端到端语音模型等方案。

### 知识库管理模块

- **三表 + 版本管理**：文档主表 + 版本表 + 分段表（对齐 know-engine），支持语义版本号、版本切换、激活/失效、旧版本自动清理。
- **文档智能处理**：MinerU 解析 PDF/DOC/HTML 为 Markdown（保留标题层级/表格/公式，Tika fallback），MarkdownHeaderBrotherTextSplitter 按标题切块。
- **Spring 事件异步向量化**：切块完成发布 `DocumentChunkedEvent`，`@Async` + `AFTER_COMMIT` 异步嵌入写 Elasticsearch；失败由 `@Scheduled` 补偿任务重试。
- **RAG 检索增强**：Elasticsearch 向量存储 + LangChain4j `DefaultRetrievalAugmentor`（`ContentRetriever`/`ContentAggregator`/`QueryTransformer`/`QueryRouter`），查询改写 + RRF 融合 + Rerank 提升 AI 问答准确性与专业度。
- **流式响应交互**：基于 SSE（Server-Sent Events）技术实现打字机式流式响应。
- **智能问答对话**：支持会话管理、置顶、多知识库关联、Markdown 展示和虚拟列表渲染。
- **知识库运维**：支持分类管理、下载、重新向量化、搜索和统计信息展示。

### 多模型与系统设置模块

- **多 Provider 管理**：内置 DashScope、LM Studio、Kimi、DeepSeek、GLM 等 OpenAI 兼容 Provider 配置。
- **默认模型切换**：支持在设置页切换默认聊天模型和默认向量模型，不需要频繁修改源码配置。
- **语音服务配置**：ASR/TTS 配置可视化管理，支持语音服务连通性测试。
- **配置安全落盘**：运行时配置默认写入用户目录 `~/.interview-guide/`，支持 API Key 加密配置。

## 效果展示

### 简历与面试

面试中心：

![面试中心](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-interview-hub.png)

Skill 出题 + JD 解析：

![Skill 出题 + JD 解析](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-skill-jd-parse.png)

简历库：

![简历库](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-history.png)

简历上传分析：

![简历上传分析](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-upload-analysis.png)

简历分析详情：

![简历分析详情](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-resume-analysis-detail.png)

面试记录：

![面试记录](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-interview-history.png)

面试详情：

![面试详情](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-interview-detail.png)

模拟面试：

![模拟面试](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-mock-interview.png)

面试安排

![面试安排](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-interview-schedule-list.png)

多模型切换 + 语音服务设置：

![管理聊天模型、向量模型和模块配置](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/llm-settings.png)


### 知识库

知识库管理：

![知识库管理](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-knowledge-base-management.png)

问答助手：

![问答助手](https://oss.javaguide.cn/xingqiu/pratical-project/interview-guide/page-qa-assistant.png)

## 项目结构

```
interview-guide/
├── backend/                          # 后端 Maven 模块
│   ├── src/main/java/interview/guide/
│   │   ├── App.java                  # 主启动类
│   │   ├── common/                   # 通用基础能力
│   │   │   ├── ai/                   # LLM Provider、结构化输出、Prompt 安全
│   │   │   ├── annotation/           # @RateLimit 可重复限流注解
│   │   │   ├── aspect/               # RateLimitAspect + Redis Lua 限流
│   │   │   ├── async/                # Redis Stream 生产者/消费者模板
│   │   │   ├── config/               # CORS、S3、OpenAPI、Jackson 等配置
│   │   │   ├── evaluation/           # 文字/语音共用的统一评估引擎
│   │   │   ├── exception/            # 业务异常与全局异常处理
│   │   │   └── result/               # 统一响应 Result<T>
│   │   ├── infrastructure/           # 基础设施
│   │   │   ├── export/               # PDF 导出
│   │   │   ├── file/                 # 文件解析、校验、清洗、S3 存储
│   │   │   ├── mapper/               # MapStruct 映射器
│   │   │   └── redis/                # RedisService、面试会话缓存
│   │   └── modules/                  # 业务模块
│   │       ├── interview/            # 模拟面试模块
│   │       │   ├── agent/            # ReAct Agent 出题（含工具：知识库检索、简历读取）
│   │       │   ├── skill/            # Skill 管理（10+ 方向 + JD 解析）
│   │       │   ├── listener/         # 异步评估 Stream 消费者
│   │       │   └── service/          # 会话管理、出题、追问、评估
│   │       ├── interviewschedule/    # 面试安排模块
│   │       ├── knowledgebase/        # 知识库模块（三表 + 版本管理 + Spring 事件向量化 + RAG）
│   │       │   ├── constant/         # DocumentStatus/SegmentStatus/SplitType/FileType 状态机
│   │       │   ├── config/           # ElasticSearchConfiguration、MineruProperties
│   │       │   ├── event/            # DocumentChunkedEvent + DocumentEventListener（@Async+AFTER_COMMIT）
│   │       │   ├── job/              # DocumentCompensationJob（@Scheduled 向量化补偿 + 旧版本清理）
│   │       │   ├── model/            # KnowledgeBaseEntity + VersionEntity + SegmentEntity（三表）
│   │       │   ├── rag/              # ContentRetriever/Aggregator/QueryTransformer/QueryRouter（对齐 know-engine）
│   │       │   ├── repository/       # KnowledgeBase + Version + Segment Repository
│   │       │   ├── service/          # DocumentProcessService 编排、版本管理、分段、VectorStore、RAG 查询、Rerank
│   │       │   │   └── parse/        # FileProcessService 工厂 + MineruProcessService + MarkdownProcessService
│   │       │   └── service/splitter/ # MarkdownHeaderParent/BrotherTextSplitter
│   │       ├── llmprovider/          # 多模型 Provider 与语音配置
│   │       │   └── service/          # API Key 加密、连通性测试、启动加载
│   │       ├── resume/               # 简历模块
│   │       │   └── service/          # 上传、解析、评分、去重、历史记录
│   │       └── voiceinterview/       # 语音面试模块
│   │           ├── handler/          # WebSocket 处理器（实时字幕、VAD 断句）
│   │           └── service/          # 语音服务（流式 TTS、并发合成）
│   └── src/main/resources/
│       ├── application.yml           # 应用配置
│       ├── prompts/                  # AI 提示词模板（StringTemplate）
│       ├── scripts/                  # Redis Lua 脚本（限流）
│       ├── skills/                   # 面试 Skill 定义和参考题库
│       │   ├── java-backend/         # Java 后端通用
│       │   ├── ali-backend/          # 阿里后端专项
│       │   ├── bytedance-backend/    # 字节后端专项
│       │   ├── java-backend-tencent/ # 腾讯后端专项
│       │   ├── frontend/             # 前端通用
│       │   ├── python-backend/       # Python 后端
│       │   ├── algorithm/            # 算法
│       │   ├── system-design/        # 系统设计
│       │   ├── test-development/     # 测试开发
│       │   ├── ai-agent-dev/         # AI Agent 开发
│       │   └── _shared/references/   # 共享参考题库（Java/MySQL/Redis/Spring 等）
│       └── voice-interview-opening.yml # 语音面试开场白配置
│
├── frontend/                         # 前端应用
│   ├── src/
│   │   ├── api/                      # API 接口
│   │   ├── components/               # 公共组件
│   │   ├── hooks/                    # 业务 Hooks
│   │   ├── pages/                    # 页面组件
│   │   ├── types/                    # 类型定义
│   │   └── utils/                    # 工具函数
│   ├── package.json
│   └── vite.config.ts
│
├── docker-compose.yml                # 完整部署：前端 + 后端 + PostgreSQL + Redis + MinIO
├── docker-compose.dev.yml            # 本地开发依赖：PostgreSQL + Redis + RustFS
├── AGENTS.md                         # 项目编码规范（分层架构、错误码、限流、异步任务等）
├── .env.example                      # 环境变量示例
└── README.md
```

## 快速开始

环境要求：

| 依赖          | 版本 | 必需 | 说明                                     |
| ------------- | ---- | ---- | ---------------------------------------- |
| JDK           | 21+  | 是   | 开发语言                                 |
| Node.js       | 18+  | 是   | 前端构建                                 |
| pnpm          | 10+  | 推荐 | 前端包管理器（项目 packageManager 指定 10.26）|
| Docker        | -    | 推荐 | 一键启动依赖服务（PostgreSQL/Redis/RustFS）|

> 如果不用 Docker，需要自行安装 PostgreSQL 14+、Elasticsearch 8.x（向量存储）、Redis 6+ 和 S3 兼容存储。
>
> **向量存储说明**：已从 pgvector 迁移到 Elasticsearch。`docker-compose.dev.yml` 和 `docker-compose.yml` 均已内置 ES 服务（`docker.elastic.co/elasticsearch/elasticsearch:8.17.0`，单节点 + 关闭安全认证，端口 9200），随 `docker compose up` 自动拉起，无需单独启动。

### 1. 克隆项目

```bash
git clone https://github.com/Snailclimb/interview-guide.git
cd interview-guide
```

### 2. 配置环境变量

推荐复制 `.env.example` 为 `.env`，后端 `bootRun` 会自动读取根目录 `.env`。最少需要填写 `AI_BAILIAN_API_KEY`，用于 DashScope 文本模型、ASR 和 TTS：

```bash
cp .env.example .env

# 编辑 .env
# AI_BAILIAN_API_KEY=your_dashscope_api_key
# AI_MODEL=qwen3.5-flash
```

如果你更习惯通过 shell 环境变量注入，也可以这样设置：

```bash
# macOS / Linux（zsh）
echo 'export AI_BAILIAN_API_KEY=your_api_key' >> ~/.zshrc
source ~/.zshrc

# Linux（bash）
echo 'export AI_BAILIAN_API_KEY=your_api_key' >> ~/.bashrc
source ~/.bashrc
```

### 3. 启动依赖服务（可选）

项目提供了 `docker-compose.dev.yml`，可一键启动 PostgreSQL、Redis、RustFS（S3 兼容存储）三个依赖：

```bash
# 启动依赖服务
docker compose -f docker-compose.dev.yml up -d

# 停止依赖服务
docker compose -f docker-compose.dev.yml down

# 停止并清除数据
docker compose -f docker-compose.dev.yml down -v
```

启动后默认账号：

| 服务         | 地址             | 账号            | 密码            |
| ------------ | ---------------- | --------------- | --------------- |
| PostgreSQL   | `localhost:5432` | `postgres`      | `123456`        |
| Redis        | `localhost:6379` | -               | -               |
| RustFS 控制台 | `localhost:9001` | `rustfsadmin`   | `rustfsadmin`   |

> **注意**：首次启动后需浏览器访问 [http://localhost:9001](http://localhost:9001) 登录 RustFS 控制台，手动创建名为 `interview-guide` 的 Bucket。使用 `docker-compose.dev.yml` + `mvn -pl backend spring-boot:run` 时，请确保 `.env` 中的 `APP_STORAGE_ACCESS_KEY` / `APP_STORAGE_SECRET_KEY` 与 RustFS 账号一致，例如都设为 `rustfsadmin`。如果本地已有 MinIO 或其他 S3 兼容存储，也可以直接使用，在 `.env` 中修改 `APP_STORAGE_*` 配置即可。

### 4. 启动应用

**后端：**

```bash
mvn -pl backend spring-boot:run
```

后端服务启动于 `http://localhost:8080`

**前端：**

```bash
cd frontend
corepack enable
pnpm install
pnpm dev
```

前端服务启动于 `http://localhost:5173`


## Docker 快速部署

本项目提供了完整的 Docker 支持，可以一键启动所有服务（前后端、数据库、中间件）。

Docker Compose 编排了 7 个服务：PostgreSQL、Elasticsearch（向量存储）、Redis、MinIO（S3 兼容存储）、MinIO Bucket 初始化、Spring Boot 后端、React 前端（Nginx）。数据通过 Docker 命名卷持久化，`docker-compose down` 不会丢失数据。

### 1. 前置准备

- 安装 [Docker](https://www.docker.com/products/docker-desktop/) 和 Docker Compose
- 申请阿里云百炼 API Key（用于 AI 对话功能，申请地址：<https://bailian.console.aliyun.com/>）

### 2. 快速启动

在项目根目录下执行：

`.env.example` 中的 PostgreSQL、Redis、MinIO 已与 `docker-compose.yml` 对齐（数据库用户 `postgres` / 密码 `password`，MinIO `minioadmin` / `minioadmin`）。复制为 `.env` 后主要填写 `AI_BAILIAN_API_KEY`；若你曾在旧版本中使用过不同的库密码或对象存储密钥，请同步修改 `.env`，必要时重建 Postgres 卷以免旧数据与密码不一致。

```bash
# 1. 复制环境变量配置文件
cp .env.example .env

# 2. 编辑 .env 文件，填入 AI 配置
# vim .env
# 必填：AI_BAILIAN_API_KEY=your_key_here
# 可选：AI_MODEL=qwen3.5-flash   # 默认值为 qwen3.5-flash
# 也可以在设置页维护 DashScope、Kimi、DeepSeek、GLM、LM Studio 等 Provider
#
# 面试参数配置（可选）：
# APP_INTERVIEW_FOLLOW_UP_COUNT=1         # 每个主问题生成追问数量（默认 1）
# APP_INTERVIEW_EVALUATION_BATCH_SIZE=8   # 回答评估分批大小（默认 8）
# APP_AI_CONFIG_ENCRYPTION_KEY=32_chars   # 可选：运行时 Provider API Key 加密密钥

# 3. 构建并启动所有服务
docker-compose up -d --build
```

> **仅启动依赖服务**：如果只想本地开发调试（用 `mvn -pl backend spring-boot:run` 启动后端），可以只启动基础设施：`docker compose up -d postgres redis minio createbuckets`。将 `.env.example` 复制为 `.env` 并填写 `AI_BAILIAN_API_KEY` 即可，默认账号与 `docker-compose.yml` 一致。

### 3. 服务访问

启动完成后，您可以通过以下地址访问各个服务：

| 服务             | 地址                                           | 默认账号     | 默认密码     | 说明                   |
| ---------------- | ---------------------------------------------- | ------------ | ------------ | ---------------------- |
| **前端应用**     | [http://localhost](http://localhost)           | -            | -            | 用户访问入口           |
| **后端 API**     | [http://localhost:8080](http://localhost:8080) | -            | -            | RESTful API            |
| **接口文档**     | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | - | - | SpringDoc/Swagger UI |
| **MinIO 控制台** | [http://localhost:9001](http://localhost:9001) | `minioadmin` | `minioadmin` | 对象存储管理           |
| **MinIO API**    | `localhost:9000`                               | -            | -            | S3 兼容接口            |
| **PostgreSQL**   | `localhost:5432`                               | `postgres`   | `password`   | 业务关系数据库         |
| **Elasticsearch**| `localhost:9200`                                | -            | -            | 向量存储（替代 pgvector，compose 内置）|
| **Redis**        | `localhost:6379`                               | -            | -            | 缓存与消息队列         |

### 4. 常用运维命令

```bash
# 查看服务状态
docker-compose ps

# 查看后端日志
docker-compose logs -f app

# 拉取新代码后重新构建部署
docker-compose up -d --build

# 停止并移除所有服务（数据保留在 Docker 卷中）
docker-compose down

# 停止服务并清除数据卷（慎用，会删除数据库和文件）
docker-compose down -v

# 清理无用镜像（构建产生的中间层）
docker image prune -f
```

## 使用场景

| 用户角色        | 使用场景                               |
| --------------- | -------------------------------------- |
| **求职者**      | 上传简历获取分析建议，进行模拟面试练习 |
| **HR/招聘人员** | 批量分析简历，评估候选人能力           |
| **培训机构**    | 提供面试培训服务，管理知识库资源       |

## 常见问题

### Q: 数据库表创建失败/数据丢失

检查 JPA 的 `ddl-auto` 配置。`ddl-auto` 模式对比：

| 模式     | 行为                            | 适用场景      | 数据保留 |
| -------- | ------------------------------- | ------------- | -------- |
| **update** | 智能模式：表不存在自动创建，存在则增量更新 | **开发环境（推荐）** | ✅ 保留 |
| create   | 无条件删除并重建所有表          | 仅首次建表时使用 | ❌ 删除 |
| validate | 只验证，不修改                  | 生产环境      | ✅ 保留 |
| none     | 什么都不做                      | 生产环境      | ✅ 保留 |

**推荐配置（已默认）**：

```yaml
jpa:
  hibernate:
    ddl-auto: update  # 首次启动自动创建表，后续保留数据并增量更新
```

⚠️ **注意**：避免使用 `create` 模式，否则每次重启都会删除所有数据！

### Q: 知识库向量化失败

知识库向量化已从 pgvector 迁移到 Elasticsearch + Spring 事件编排（对齐 know-engine）。排查思路：

1. **Elasticsearch 连通性**：检查 `elasticsearch.host` 配置和 ES 服务是否启动，向量索引（默认 `interview-guide-vector`）是否就绪。
2. **状态机**：文档/版本状态 `CONVERTED → CHUNKED → VECTOR_STORED`。停在 `CHUNKED` 表示向量化未完成，`@Scheduled` 补偿任务（每 5 分钟）会自动重试；也可手动调 `POST /api/document/activate-version`。
3. **MinerU 解析**：`file.parse.mineru.enabled=false` 或 MinerU 服务不可达时自动降级 Tika；若 Markdown 解析为空，检查原文件格式。
4. **Embedding 模型**：向量化用 `LlmProviderRegistry.getDefaultEmbeddingModel()`（DashScope text-embedding-v3，1024 维），确认默认 Provider 的 embedding 模型配置正确。

> 旧 pgvector `vector_store` 表 + `spring.ai.vectorstore.pgvector.initialize-schema` 配置已废弃。

### Q: 简历分析失败

检查一下阿里云 DashScope API KEY 是否配置正确（申请地址：<https://bailian.console.aliyun.com/>）。

### Q: 设置页新增/切换模型后不生效？

运行时 Provider 配置默认写到 `~/.interview-guide/llm-providers.yml` 和 `~/.interview-guide/llm-providers.env`。可以在设置页点击测试连接，或调用 `/api/llm-provider/reload` 重新加载配置。Docker 部署时如果希望配置持久化，建议为该目录挂载卷。

### Q: 语音面试无法识别或没有声音？

语音面试的 ASR/TTS 默认也使用 `AI_BAILIAN_API_KEY`。请检查浏览器麦克风权限、后端日志中的 DashScope WebSocket 连接状态，以及设置页里的 ASR/TTS 测试结果。无耳机时可能触发回声录入，建议先使用手动提交模式或佩戴耳机测试。

### Q: 简历分析一直显示"分析中"？

检查 Redis 连接和 Stream Consumer 是否正常运行。查看后端日志确认是否有错误。

### Q: PDF 导出失败或中文显示异常？

项目已内置中文字体（珠圆玉润仿宋），支持跨平台导出。如遇到问题，请检查：
- 字体文件是否存在：`backend/src/main/resources/fonts/ZhuqueFangsong-Regular.ttf`
- 检查日志中的字体加载信息
- 确认 iText 依赖是否正确

### Q: Windows PowerShell 下后端日志中文乱码？

**原因简述**：后端与 Logback 按 **UTF-8** 输出日志；中文 Windows 下控制台默认多为 **GBK（代码页 936）**，且 PowerShell 的 `$OutputEncoding`、控制台编码若未统一为 UTF-8，显示时就会把同一串字节解释错，出现乱码。

**本项目已做的配置**（一般无需再改）：根目录 `pom.xml`（Maven 编译编码）、`backend/src/main/resources/logback-spring.xml`（控制台日志 UTF-8）、`backend/pom.xml` 中 `spring-boot-maven-plugin` 的 JVM 参数（含 `file.encoding` / `stdout.encoding` / `stderr.encoding`）。

**仍乱码时（PowerShell 侧）**：在启动 `mvn -pl backend spring-boot:run` 的同一终端先执行下面一段；或写入 **PowerShell 配置文件**（`$PROFILE`）以便每次自动生效：

```powershell
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding  = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
```

新建或编辑配置文件：`if (!(Test-Path $PROFILE)) { New-Item -Path $PROFILE -ItemType File -Force }`，再 `notepad $PROFILE` 将上述内容粘贴保存；新开终端后生效，或执行 `. $PROFILE` 立即加载。若提示脚本无法执行，可执行一次：`Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned`。

在 PowerShell 中建议从仓库根目录执行 `mvn -pl backend spring-boot:run`，避免模块路径不一致导致配置文件加载失败。

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

AGPL-3.0 License（只要通过网络提供服务，就必须向用户公开修改后的源码）
