# AI Interview Frontend

React 18 + TypeScript + Vite 前端，提供知识库管理、RAG 问答、RAG Trace、简历管理、模拟面试、语音面试和模型设置页面。

## 启动

```bash
pnpm install
pnpm dev
```

默认地址：

```text
http://localhost:5174
```

开发代理：

- `/api` -> `http://localhost:8082`
- `/ws` -> `ws://localhost:8082`

完整 Docker 环境使用 Nginx 服务：

```text
http://localhost:28080
```

## 构建

```bash
pnpm build
```

## 关键目录

- `src/api`：统一请求封装和模块 API。
- `src/pages`：知识库、RAG 对话、简历、面试、设置页面。
- `src/components`：通用组件和业务组件。
- `nginx.conf`：容器化部署时的静态资源服务和 API 反代。
