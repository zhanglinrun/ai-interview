# API 与密钥配置

开发环境从仓库根目录 `.env` 读取配置，生产环境使用服务器 `dev-ops/.env`。两者均已被 Git 忽略；
真实 Key 不能进入源码、日志、截图、Smoke 报告或提交记录。完整变量及默认值分别以
`.env.example`、`dev-ops/.env.prod.example` 和 `backend/src/main/resources/application.yml` 为准。

## 安全与平台模型

```properties
# 平台默认模型、Embedding 与云 Rerank 使用的百炼 / DashScope Key
AI_BAILIAN_API_KEY=
AI_MODEL=qwen3.5-flash

# JWT 签名密钥，生产环境使用至少 32 字节强随机值
APP_JWT_SECRET=

# AES-GCM 加密用户 BYOK；生产环境必填，保存过密文后不可更换
APP_AI_CONFIG_ENCRYPTION_KEY=
APP_AI_CONFIG_REQUIRE_ENCRYPTION_KEY=true
```

普通用户在“我的模型”页面配置自己的 OpenAI-compatible Provider。用户触发的 Chat、Streaming 和
Structured Output 按 userId 路由到 BYOK；API Key 加密保存，查询接口只返回“已配置 / 未配置”。
不要把用户 BYOK 写入服务器共享环境变量。

## MinerU 官方异步 API

MinerU 运行在外部官方服务，不在 4C6G 主机部署模型。应用为 MinIO 私有对象生成短时预签名 URL，
调用 `/api/v4/extract/task`，有界轮询任务，下载并安全校验 ZIP 后读取 `full.md`。官方 API 失败时会将
解析任务标记为降级并尝试 Apache Tika，不会伪装成 MinerU 成功。

```properties
MINERU_ENABLED=true
MINERU_BASE_URL=https://mineru.net
MINERU_API_TOKEN=
MINERU_MODEL_VERSION=vlm
MINERU_POLL_INTERVAL_MS=2000
MINERU_TASK_TIMEOUT_SECONDS=300
MINERU_PRESIGNED_URL_TTL_SECONDS=600

# 生产环境必须是 MinerU 可访问的 HTTPS files 域名
MINIO_EXTERNAL_ENDPOINT=https://files.example.com
```

`MINIO_EXTERNAL_ENDPOINT` 只用于短时签名对象读取，不能开放桶列表、匿名读、写入或 MinIO 控制台。

## GitHub 公开仓库与只读 MCP

GitHub REST API 负责固定 SHA 和选择性同步。公开仓库匿名读取可以工作，配置平台只读 Token 可提高
限额。系统不接受候选人 PAT，不读取私库，也不执行 GitHub 写操作。

```properties
GITHUB_ENABLED=true
GITHUB_API_BASE_URL=https://api.github.com
GITHUB_PUBLIC_TOKEN=
GITHUB_SYNC_MAX_FILES=120
GITHUB_SYNC_MAX_BYTES=10485760

# 可选：GitHub 官方远程只读 MCP；关闭或失败时回退固定 SHA 快照
GITHUB_MCP_ENABLED=false
GITHUB_MCP_ENDPOINT=https://api.githubcopilot.com/mcp/
GITHUB_MCP_ACCESS_TOKEN=
GITHUB_MCP_MAX_RESPONSE_BYTES=1048576
```

GitHub MCP 是本平台主动调用的受限 Client，不是平台向外暴露的 MCP Server。启用后仍受
owner / repo / SHA、工具名和响应大小白名单约束。

## Judge0 外部客观判题

Judge0 只接收候选人源码和最小测试驱动，不接收 JD、简历、GitHub 正文或面试回答。未配置时题库、
草稿和过程评价仍可用，提交状态显示“待补判”；LLM 不会生成虚假的通过结论。

```properties
JUDGE0_ENABLED=false
JUDGE0_BASE_URL=
JUDGE0_API_KEY=
JUDGE0_API_HOST=
JUDGE0_JAVA21_LANGUAGE_ID=
JUDGE0_PYTHON3_LANGUAGE_ID=
JUDGE0_CONNECT_TIMEOUT_MS=5000
JUDGE0_REQUEST_TIMEOUT_MS=10000
JUDGE0_POLL_INTERVAL_MS=1000
JUDGE0_TIMEOUT_SECONDS=30
JUDGE0_CPU_TIME_LIMIT_SECONDS=3
JUDGE0_MEMORY_LIMIT_KB=262144
```

不同 Judge0 服务商的 Java 21 / Python 3 language id 可能不同，必须从实际实例查询后填写，不能照抄
其他服务商的编号。

## 验证原则

- 启动和配置查询不得回显任何 Key；日志不得输出 Authorization、签名 URL、Prompt、源码或隐藏用例。
- 外部 API 的单元 / 契约测试使用 Stub 或 Mock，不把真实响应固化进仓库。
- 真实联调使用低额度账号和非敏感 fixture，并保留只含状态、业务 ID 和计数的脱敏报告。
- 编译、Mock 或本地参考实现通过，不等于 MinerU、GitHub MCP 或 Judge0 真实 E2E 已验收。
