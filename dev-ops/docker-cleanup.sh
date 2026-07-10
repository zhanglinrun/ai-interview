#!/usr/bin/env bash
# Docker 定期清理脚本
# 只回收：构建缓存(builder cache) + 悬空镜像(<none>，被新镜像替换后残留的)。
# 绝不触碰：运行中/已停止的业务容器、命名数据卷(mysql/es/minio/rabbitmq 数据)、在用的带 tag 镜像。
# 用法：手动 `bash docker-cleanup.sh`；或由 crontab 定期调用。
set -uo pipefail

LOG="${HOME}/docker-cleanup.log"

# docker 权限：优先免 sudo（ubuntu 已加入 docker 组），不行则回退 sudo（NOPASSWD）
DOCKER="docker"
$DOCKER ps >/dev/null 2>&1 || DOCKER="sudo docker"

{
  echo "===== $(date '+%F %T') docker cleanup ====="
  echo "[before] disk: $(df -h / | awk 'NR==2{print $3"/"$2" ("$5")"}')"

  # 1) 构建缓存（重建镜像时会自动重建，删除安全）
  $DOCKER builder prune -f

  # 2) 悬空镜像（untagged <none>，多为重建后被替换的旧层；-f 不带 -a，不删在用带 tag 镜像）
  $DOCKER image prune -f

  echo "[after]  disk: $(df -h / | awk 'NR==2{print $3"/"$2" ("$5")"}')"
  echo "[docker system df]"
  $DOCKER system df
  echo "===== done ====="
  echo
} >> "$LOG" 2>&1

# 让日志本身不无限增长：只保留最近 300 行
tail -n 300 "$LOG" 2>/dev/null > "${LOG}.tmp" && mv "${LOG}.tmp" "$LOG"
