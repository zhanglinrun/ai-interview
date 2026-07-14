# 部署上线 Runbook（大陆 4C4G）

配套文件（本目录）：`docker-compose-ip.yml`（阶段一）、`docker-compose-prod.yml` + `Caddyfile`（阶段二）、`.env.prod.example`。

生产配置常驻容器硬上限约为 3.1 GiB（IP 模式约 3.0 GiB），主线保留 Hybrid Search、RRF、
云端 Rerank 和 small-to-big 上下文扩展。Graph/MCP/Text2SQL/CRAG/GitHub 工具在 4C4G 配置中
显式关闭；它们是可选实验能力，不参与普通用户面试链路。

> 说明：Agent 无法登录你的服务器，以下命令都在**你的大陆服务器 SSH 里**执行；遇报错把输出贴回来。
> **不要在聊天里发服务器密码。**

## 两阶段上线

- **阶段一（现在）· IP 直连**：`http://<服务器IP>:8080`，**无需备案、无需 DNS**，当天可上。
  用 `docker-compose-ip.yml`。跳过下面的 Step 0（备案）和 Step 4（DNS）。
  - 限制：① 语音用不了（`getUserMedia` 需 HTTPS）；② BYOK 填 Key 走明文传输（自测无妨，别让外人此时输真实 Key）。文字问答/出题/简历全可用。
- **阶段二（备案通过后）· 域名 + HTTPS**：切到 `docker-compose-prod.yml` + `Caddyfile`，走完 Step 0/4。
  同一 `.env`、同一批 volume，切换零丢数据：
  ```bash
  docker compose --env-file .env -f docker-compose-ip.yml down
  docker compose --env-file .env -f docker-compose-prod.yml up -d --build
  ```

下面 Step 1/2/3/6 两阶段通用；**阶段一在 Step 5 用 `docker-compose-ip.yml`**。

---

## Step 0 —【硬门槛】确认备案（先做，别跳）

大陆服务器上未备案的域名，80/443 会被管局/ISP 拦截，Caddy 也签不到证书。

1. 腾讯云「备案控制台」或工信部 https://beian.miit.gov.cn 查 `xiaoxiong123.cloud`：
   - **有备案号 + 主体是你 + 接入商腾讯云** → 若这台 4C4G 也是腾讯云同账号，做「**新增接入**」把这台 IP 挂上（比新备案快）。
   - **查不到 / 没备过** → 必须先备案（1-3 周），期间无法用该域名在大陆机上线。
2. 备案通过后再继续。（api 现在跑海外不代表大陆能用同域名——以备案控制台为准。）

---

## Step 1 — 服务器准备

```bash
# 1) 装 Docker + compose 插件（Ubuntu/Debian 示例）
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker

# 2) ES 必需：加大 mmap 计数（否则 ES 起不来）
sudo sysctl -w vm.max_map_count=262144
echo 'vm.max_map_count=262144' | sudo tee -a /etc/sysctl.conf

# 3) 4G 偏紧，加 2G swap 兜底（构建/峰值更稳）
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# 4) 安全组/防火墙放行端口（腾讯云控制台「安全组」入站规则）
#    阶段一(IP)：放行 8080（WEB_HOST_PORT）
#    阶段二(域名)：放行 80 + 443
```

---

## Step 2 — 把代码放到服务器

二选一：

```bash
# A) git（仓库已推到服务器可访问的 remote）
git clone <你的仓库地址> ai-interview && cd ai-interview

# B) 本地 scp 源码上去（无 remote 时）
#   本地执行： scp -r e:/javaproject/ai-interview user@server:/home/user/ai-interview
```

---

## Step 3 — 配置 .env

```bash
cd ai-interview/dev-ops
cp .env.prod.example .env
# 生成强随机值填进去（每条命令跑一次，结果分别填 JWT/加密key/各密码）
openssl rand -base64 48
# 用 vim/nano 编辑 .env，务必填全 APP_JWT_SECRET、APP_AI_CONFIG_ENCRYPTION_KEY、
# AI_BAILIAN_API_KEY（平台全局 embedding key）、MYSQL_ROOT_PASSWORD、MYSQL_PASSWORD、
# MINIO_ACCESS_KEY、MINIO_SECRET_KEY、RABBITMQ_PASSWORD
nano .env
```

同时把 `Caddyfile` 里的 `email admin@xiaoxiong123.cloud` 改成你的真实邮箱。

---

## Step 4 — DNS 解析

腾讯云 DNS 控制台给 `xiaoxiong123.cloud` 加一条：

```
类型 A   主机记录 interview   记录值 <服务器公网 IP>
```

等生效（`ping interview.xiaoxiong123.cloud` 解析到你的 IP 即可）。

---

## Step 5 — 构建并启动

> 你选了「服务器上构建」。4G 机构建 Maven 峰值 1-2G，**此时其它容器还没起，OK**；已加 swap 更稳。

```bash
cd ai-interview/dev-ops

# 阶段一（IP 直连）：
docker compose --env-file .env -f docker-compose-ip.yml up -d --build
docker compose --env-file .env -f docker-compose-ip.yml ps         # 看健康状态
docker logs -f interview-app                                        # 等 "Started App in ..."
# 安全组放行 ${WEB_HOST_PORT:-8080} 后，浏览器开 http://<服务器公网IP>:8080

# 阶段二（域名+HTTPS，备案通过后）：
# docker compose --env-file .env -f docker-compose-ip.yml down
# docker compose --env-file .env -f docker-compose-prod.yml up -d --build
# docker logs -f interview-caddy   # 看 Let's Encrypt 证书签发（需 Step 0 备案 + Step 4 DNS + 80/443）
```

首次构建较慢（拉基础镜像 + Maven/Vite 构建），耐心等。

---

## Step 6 — 首次配置 + 验证

1. 浏览器开：阶段一 `http://<服务器公网IP>:8080`；阶段二 `https://interview.xiaoxiong123.cloud`（带锁）。
2. 注册第一个账号（即管理员/你自己），登录。
3. **全局 Embedding**：默认走 `.env` 的 `AI_BAILIAN_API_KEY`（dashscope provider），开箱即用；
   如问答/向量化报 embedding 相关错，进「设置」确认默认 Embedding Provider 为 dashscope。
4. **BYOK 验证**：首次登录弹「配置你的模型 Key」向导 → 填任意 OpenAI 兼容 chat（如 DashScope
   `https://dashscope.aliyuncs.com/compatible-mode/v1` + qwen3.5-flash + 你的 key）→ 测试连通 → 保存。
5. 走一遍：出题/问答（走你自己 Key）、上传知识库（embedding 走平台 key）、语音面试（麦克风需 HTTPS）。
6. `docker stats` 看内存，确认没有容器濒临 OOM。

在保存好根目录 `.env` 的 Windows 开发机上，可运行真实主链路验收（会创建测试用户、知识库和
三题面试数据）：

```powershell
pwsh ./dev-ops/smoke-test.ps1 -BaseUrl https://interview.xiaoxiong123.cloud
```

---

## 排障速查

| 现象 | 可能原因 / 处理 |
|---|---|
| Caddy 证书签不下来 | 备案未过 / 80 未放行 / DNS 未生效；`docker logs interview-caddy` 看 ACME 报错 |
| ES 容器反复重启 | 忘了 `vm.max_map_count=262144`；或内存不足（`docker stats`） |
| app 启动 OOM / 被杀 | 确认 `mem_limit: 896m` 与 Compose 中的 `JAVA_OPTS` 生效；swap 已开；用 `docker stats` 判断是否需要升配 |
| 构建时 OOM | 别与其它容器同时构建；已加 swap；或改用本地构建镜像后 scp/registry |
| 上传大文件 413 | frontend nginx 已设 300M；Caddy 默认不限；确认走的是 https 域名 |
| 语音无法用麦克风 | 必须 https（已满足）；检查浏览器麦克风授权 |

## 升配到 8G 想启图谱（可选）

在 `docker-compose-prod.yml` 加回 Neo4j 服务（参考 `docker-compose-environment.yml`），给 app 补
`NEO4J_URI/USER/PASSWORD`，并把 Graph 相关开关改为 `true`。不要只改 `.env`：4C4G Compose
故意把这些开关固定为关闭，防止缺少依赖时误启动。
