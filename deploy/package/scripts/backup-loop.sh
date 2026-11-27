#!/usr/bin/env bash
# backup 容器的主程序（compose.yml 的 backup 服务），不用手动执行。
# 每分钟看一次：过了 BACKUP_TIME、今天的定时备份还没做，就调 backup.sh 备份一次。
#
# 用循环不用 cron：容器里不用另装 cron，输出直接进 docker compose logs backup。
# 按"今天做没做"判断，而不是"是不是正好这一分钟"：BACKUP_TIME 那一刻服务器关着机、容器在重启也不要紧，起来以后马上补一次。
# 刚装好时如果已经过了 BACKUP_TIME，等应用建好表就备份一次（一两分钟），装完就能看到备份是好的。失败了一小时后再试。
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
tables_since=0                  # 第一次看到库里有表的时间（db_ready）
waiting_said=''
loop_started=$(date +%s)        # 容器起来的时间（db_ready：开机后数据库要一会儿才接受连接）

# db_ready <现在>：库里有没有应用的表。刚装好时 backup 容器和应用一起启动，应用要过一会儿才建好表，
# 这时备份出来的是个空库：没用，还让人以为装完就有了一份好备份（11-14 装试用环境时就是这样）。所以等表建好再备份。
# 看到表以后再等一分钟：应用第一次启动时在一张张建表，碰巧那一刻备份的话只有一半的表。
# 连不上数据库：容器起来 5 分钟内算"还没好"，下一分钟再看。服务器重启、Docker 重启时 backup 和数据库一起起来，
# 数据库要几秒到一两分钟才接受连接，过了 BACKUP_TIME 的补备份一上来就去连，会白记一条失败、一小时后才补上（11-27 试用环境）。
# 过了 5 分钟还连不上照常去备份：让 backup.sh 报错、记进 backup.log，有人看得到
db_ready() {
  local r
  if ! r=$(psql -XAtq -c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL" 2>/dev/null < /dev/null); then
    [ $(($1 - loop_started)) -ge 300 ]
    return
  fi
  if [ "$r" != t ]; then
    tables_since=0
    if [ -z "$waiting_said" ]; then
      echo "库里还没有表（应用第一次启动，还在建表），建好以后再备份"
      waiting_said=1
    fi
    return 1
  fi
  if [ "$tables_since" = 0 ]; then
    tables_since=$1
  fi
  [ $(($1 - tables_since)) -ge 60 ]
}

trap 'exit 0' TERM INT          # docker compose stop 时马上退出，不用等 10 秒被强制杀掉
echo "每天 $backup_time 备份一次，保留 ${BACKUP_KEEP_DAYS:-7} 天"

while true; do
  now=$(date +%s)
  today=$(date +%Y%m%d)
  if [ "$now" -ge "$(date -d "today $backup_time" +%s)" ] && [ "$now" -ge "$retry_at" ] \
    && [ "$(cat "$state" 2>/dev/null || true)" != "$today" ] && db_ready "$now"; then
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
