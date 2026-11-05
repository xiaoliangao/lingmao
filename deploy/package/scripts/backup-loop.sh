#!/usr/bin/env bash
# backup 容器的主程序（compose.yml 的 backup 服务），不用手动执行。
# 每分钟看一次：过了 BACKUP_TIME、今天的定时备份还没做，就调 backup.sh 备份一次。
#
# 用循环不用 cron：容器里不用另装 cron，输出直接进 docker compose logs backup。
# 按"今天做没做"判断，而不是"是不是正好这一分钟"：BACKUP_TIME 那一刻服务器关着机、容器在重启也不要紧，起来以后马上补一次。
# 刚装好时如果已经过了 BACKUP_TIME，也会马上备份一次，装完就能看到备份是好的。失败了一小时后再试。
set -euo pipefail

# 备份时间、文件名都按北京时间。镜像里没有时区数据时 TZ=Asia/Shanghai 不起作用（会按 UTC 算，02:30 变成北京时间 10:30），
# 换成不依赖时区数据的写法：东八区，没有夏令时
if [ ! -e "/usr/share/zoneinfo/${TZ:-}" ]; then
  export TZ=CST-8
fi

backup_time=${BACKUP_TIME:-02:30}
case $backup_time in
  [01][0-9]:[0-5][0-9] | 2[0-3]:[0-5][0-9]) ;;
  *)
    echo "BACKUP_TIME 要写成 02:30 这样的 24 小时制时间，.env 里现在是 \"$backup_time\"" >&2
    exit 1 ;;
esac

state=/backups/.daily-done      # 最近一次定时备份成功的日期。手动备份不算，不会让当天的定时备份跳过
retry_at=0

trap 'exit 0' TERM INT          # docker compose stop 时马上退出，不用等 10 秒被强制杀掉
echo "每天 $backup_time 备份一次，保留 ${BACKUP_KEEP_DAYS:-7} 天"

while true; do
  now=$(date +%s)
  today=$(date +%Y%m%d)
  if [ "$now" -ge "$(date -d "today $backup_time" +%s)" ] && [ "$now" -ge "$retry_at" ] \
    && [ "$(cat "$state" 2>/dev/null || true)" != "$today" ]; then
    if bash /scripts/backup.sh daily; then
      echo "$today" > "$state"
      retry_at=0
    else
      retry_at=$((now + 3600))
    fi
  fi
  sleep 60 &
  wait $!
done
