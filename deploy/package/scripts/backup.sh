#!/usr/bin/env bash
# 备份数据库，在安装目录里执行：
#
#   sudo ./scripts/backup.sh                     手动备份一次，存到 backups/daily/（和每天的定时备份放一起）
#   sudo ./scripts/backup.sh pre-upgrade <版本>  升级前的备份，存到 backups/pre-upgrade/，文件名带升级前的版本号（upgrade.sh 调用）
#   sudo ./scripts/backup.sh pre-restore         恢复之前把当前的库再备份一份，存到 backups/pre-restore/（restore.sh 调用）
#
# 每天的定时备份由 backup 容器里的 backup-loop.sh 调用本脚本。在服务器上执行时，本脚本把自己交给一个临时的 backup 容器去跑：
# 容器里的 pg_dump 和数据库版本一致，服务器上也不用装 PostgreSQL 客户端。
#
# 怎么保证备份文件是好的：先写成 . 开头的临时文件，用 pg_restore 从头到尾读一遍，读得通才改成正式的名字。
# 备份到一半断了、硬盘满了，留下的只会是临时文件，不会是一个看起来正常的坏文件。
# （RFC 写的是 pg_restore --list。--list 只读文件开头的目录，数据写到一半断掉的文件它也读得通，所以改成完整读一遍）
set -euo pipefail

if [ "${SCM_BACKUP_CONTAINER:-}" != 1 ]; then
  TARGET=$(cd "$(dirname "$0")/.." && pwd)
  . "$TARGET"/scripts/lib.sh
  require_root
  cd "$TARGET"
  exec docker compose run --rm -T backup bash /scripts/backup.sh "$@"
fi

# ---- 下面在 backup 容器里执行：/backups 是安装目录的 backups/，连库用的 PGHOST、PGPASSWORD 等由 compose.yml 给 ----

# 文件名、日志里的时间按北京时间，理由见 backup-loop.sh 开头
if [ ! -e "/usr/share/zoneinfo/${TZ:-}" ]; then
  export TZ=CST-8
fi

kind=${1:-daily}
stamp=$(date +%Y%m%d-%H%M%S)
case $kind in
  daily)
    dir=/backups/daily
    name=scm-$stamp.dump ;;
  pre-upgrade)
    version=${2:-}
    case $version in
      '' | *[!0-9A-Za-z.-]*) echo "升级前备份要带上现在的版本号，比如 backup.sh pre-upgrade 2026.11.14" >&2; exit 2 ;;
    esac
    dir=/backups/pre-upgrade
    name=scm-$stamp-v$version.dump ;;
  pre-restore)
    dir=/backups/pre-restore
    name=scm-$stamp.dump ;;
  *)
    echo "用法：backup.sh [daily | pre-upgrade <版本> | pre-restore]" >&2
    exit 2 ;;
esac

# 备份里是全部业务数据：文件只给 root 读。目录要让应用（uid 10001）能列出来、看修改时间，管理后台才显示得了最近备份
umask 077
mkdir -p "$dir"
chmod 755 /backups "$dir"
tmp=$dir/.$name.tmp
log=/backups/backup.log
started=$(date +%s)

fail() {
  rm -f "$tmp"
  printf '%s %s 失败：%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$kind" "$1" | tee -a "$log" >&2
  exit 1
}

echo "备份数据库到 ${dir#/}/$name ..."
pg_dump --format=custom --file="$tmp" || fail "pg_dump 出错（上面是它的报错；常见原因：硬盘满了、数据库没起来）"
pg_restore --file=/dev/null "$tmp" || fail "备份文件读不通，没有留下"
mv "$tmp" "$dir/$name"
size=$(stat -c %s "$dir/$name")

# 清理旧备份。每天的：超过 BACKUP_KEEP_DAYS 天的删掉，但最新的那几个不管多旧都留着，
# 免得备份停了一阵子以后，第一次恢复正常就把旧的全删光。升级前、恢复前的：留最近 5 个
removed=0
if [ "$kind" = daily ]; then
  keep=${BACKUP_KEEP_DAYS:-7}
  case $keep in
    '' | 0 | *[!0-9]*) keep=7 ;;     # .env 里写错了就按 7 天，不能因为这个把备份删光
  esac
  while read -r f; do
    if [ -n "$(find "$dir/$f" -mmin +$((keep * 1440)))" ]; then
      rm -f "$dir/$f"
      removed=$((removed + 1))
    fi
  done < <(ls -1 "$dir" | grep -E '^scm-[0-9]{8}-[0-9]{6}\.dump$' | sort -r | tail -n +$((keep + 1)) || true)
else
  while read -r f; do
    rm -f "$dir/$f"
    removed=$((removed + 1))
  done < <(ls -1 "$dir" | grep -E '^scm-.*\.dump$' | sort -r | tail -n +6 || true)
fi
# 以前断掉的备份留下的临时文件
find /backups -name '.scm-*.tmp' -mmin +1440 -delete

note=''
if [ "$removed" -gt 0 ]; then
  note="，删掉 $removed 个旧备份"
fi
printf '%s %s 成功：%s，%s，用时 %d 秒%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$kind" "${dir#/}/$name" \
  "$(numfmt --to=iec "$size")" "$(($(date +%s) - started))" "$note" | tee -a "$log"
