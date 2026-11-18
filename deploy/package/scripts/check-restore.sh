#!/usr/bin/env bash
# 恢复演练：确认备份真的恢复得出来。没恢复过的备份不算备份。
#
#   sudo /opt/scm/scripts/check-restore.sh               用 backups/daily/ 里最新的备份
#   sudo /opt/scm/scripts/check-restore.sh <备份文件>    用指定的备份
#
# 把备份恢复到一个临时库 scm_restore_check，逐张表数行数，和线上的库对比，最后删掉临时库（RFC 002 §9.5）。
# 当前库可因物理删除/归档合法减少。跨时点行数只作诊断，不能证明备份完整或损坏。
# 不影响线上：不停应用，不动线上的库，系统照常可以用。
set -euo pipefail

TARGET=$(cd "$(dirname "$0")/.." && pwd)
. "$TARGET"/scripts/lib.sh

require_root
if [ $# -ge 1 ]; then
  if [ ! -f "$1" ]; then
    die "找不到文件：$1"
  fi
  file=$(abs_path "$1")
else
  file=$(newest_dump "$TARGET"/backups/daily)
  if [ -z "$file" ]; then
    die "backups/daily/ 里还没有备份。先手动备份一次：sudo $TARGET/scripts/backup.sh"
  fi
fi
src_dir=$(dirname "$file")
src_name=$(basename "$file")
cd "$TARGET"
take_lock
if [ -z "$(docker compose ps -q --status running postgres 2>/dev/null || true)" ]; then
  die "数据库没在运行。先在 $TARGET 里执行 sudo docker compose up -d"
fi

db=scm_restore_check
work=$(mktemp -d)
# 不显示"database ... does not exist, skipping"这类 NOTICE：部署人员看到会以为出错了
admin_sql() {
  docker compose exec -T postgres psql -U scm -d postgres -v ON_ERROR_STOP=1 -q -c 'SET client_min_messages = warning' "$@" < /dev/null
}
cleanup() {
  admin_sql -c "DROP DATABASE IF EXISTS $db WITH (FORCE)" > /dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# 每张表一行：表名|行数
count_rows() {
  docker compose exec -T postgres psql -U scm -d "$1" -At -v ON_ERROR_STOP=1 <<'SQL'
SELECT c.relname || '|' ||
       (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
ORDER BY 1;
SQL
}

step "把 $src_name 恢复到临时库 $db"
admin_sql -c "DROP DATABASE IF EXISTS $db WITH (FORCE)" -c "CREATE DATABASE $db OWNER scm"   # 上次演练中途断掉留下的先删掉
started=$(date +%s)
if ! docker compose run --rm -T --no-deps -v "$src_dir:/restore-src:ro" backup \
  pg_restore --no-owner --exit-on-error --dbname="$db" "/restore-src/$src_name" < /dev/null; then
  printf '%s check-restore 失败：%s 恢复不出来\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$file" >> backups/backup.log
  die "这个备份恢复不出来，上面是 pg_restore 的报错。把它发给我们"
fi
info "  恢复用了 $(($(date +%s) - started)) 秒"

step "逐张表数行数；与当前库的差异仅供诊断"
# join 要求两边都按表名排好序（只按表名：整行排序时 a_b|2 会排到 a|1 前面，join 就对不上了）
count_rows "$db" | LC_ALL=C sort -t '|' -k 1,1 > "$work"/backup.txt
count_rows scm | LC_ALL=C sort -t '|' -k 1,1 > "$work"/live.txt
tables=$(wc -l < "$work"/backup.txt | tr -d ' ')
if [ "$tables" -eq 0 ]; then
  die "恢复出来的库里一张表都没有，这个备份是空的"
fi

LC_ALL=C join -t '|' -a 1 -a 2 -e '-' -o 0,1.2,2.2 "$work"/backup.txt "$work"/live.txt | awk -F '|' '
  BEGIN { print "  表                                     备份       线上" }   # 中文按两格宽手工对齐，printf 的宽度按字节算
  {
    note = ""
    if ($3 == "-") note = "线上没有这张表"
    else if ($2 == "-") note = "备份里没有（备份以后的新版本加的表）"
    else if ($3 + 0 < $2 + 0) note = "当前行数减少（可由合法删除/归档造成）"
    printf "  %-32s %10s %10s  %s\n", $1, $2, $3, note
  }
  END {
    print ""
    print "  跨时点行数差异不判定备份完整性；同快照数据对账、业务余额和上传文件须另验。"
  }'

printf '%s check-restore 成功：%s，%s 张表\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$file" "$tables" >> backups/backup.log
info "
恢复演练通过：$src_name 可恢复（$tables 张表）；数据对账未验证。退出时清理临时库，线上未修改"
