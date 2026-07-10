#!/usr/bin/env bash
set -uo pipefail
SCRIPT="$HOME/ai-interview/dev-ops/docker-cleanup.sh"
# 去掉 Windows 上传可能带的 BOM / CR，确保 bash 能正常执行
sed -i '1s/^\xef\xbb\xbf//' "$SCRIPT" 2>/dev/null
sed -i 's/\r$//' "$SCRIPT" 2>/dev/null
chmod +x "$SCRIPT"
# 安装/刷新 cron：每周日 04:00 执行；先去重避免重复行
( crontab -l 2>/dev/null | grep -v 'docker-cleanup.sh' ; echo "0 4 * * 0 $SCRIPT >/dev/null 2>&1" ) | crontab -
echo "=== crontab ==="
crontab -l
echo "=== 立即试跑一次 ==="
bash "$SCRIPT"
echo "=== cleanup 日志(尾部) ==="
tail -n 22 "$HOME/docker-cleanup.log"
