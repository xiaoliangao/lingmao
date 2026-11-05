#!/usr/bin/env bash
# 用一个备份文件恢复数据库：
#
#   sudo /opt/scm/scripts/restore.sh <备份文件>
#
# 现在库里的数据会被备份里的内容替换掉，所以要输入"恢复"两个字确认。然后（RFC 002 §9.4）：
# 停应用 → 把现在的库再备份一份到 backups/pre-restore/（以防选错了文件）→ 删库重建 → pg_restore → 起应用 → 等版本号。
# 上传的文件不受影响（它们只增不删）。恢复以后所有人要重新登录。
set -euo pipefail

TARGET=$(cd "$(dirname "$0")/.." && pwd)
. "$TARGET"/scripts/lib.sh

require_root
if [ $# -ne 1 ]; then
  die "用法：sudo $TARGET/scripts/restore.sh <备份文件>。备份在 $TARGET/backups/ 下面，见部署手册第 7 节"
fi
if [ ! -f "$1" ]; then
  die "找不到文件：$1"
fi
file=$(abs_path "$1")
src_dir=$(dirname "$file")
src_name=$(basename "$file")
cd "$TARGET"
take_lock

# 备份文件挂进一个临时的 backup 容器，用容器里的 PostgreSQL 工具读：服务器上不用装 PostgreSQL 客户端
in_backup_container() {
  docker compose run --rm -T --no-deps -v "$src_dir:/restore-src:ro" backup "$@" < /dev/null
}

step "检查备份文件"
if ! in_backup_container pg_restore --list "/restore-src/$src_name" > /dev/null; then
  die "$file 不是有效的备份文件（pg_restore 读不了）"
fi
cat <<EOF
  文件  $file
  大小  $(du -h "$file" | cut -f1)
  时间  $(date -r "$file" '+%Y-%m-%d %H:%M')

现在库里的数据会被这个备份替换掉；恢复之前会先把现在的库备份到 backups/pre-restore/。
恢复期间系统停用，所有人恢复以后要重新登录。
EOF
printf '确认的话输入"恢复"两个字，然后按回车：'
answer=''
read -r answer || true
if [ "$answer" != 恢复 ]; then
  die "没有输入\"恢复\"，什么都没做"
fi

step "停应用和定时备份"
docker compose stop app backup

step "把现在的库备份一份"
if ! bash "$TARGET"/scripts/backup.sh pre-restore; then
  printf '现在的库备份失败（上面是报错）。还要继续吗？继续的话，现在的数据就没有备份了。要继续请输入"继续"：'
  answer=''
  read -r answer || true
  if [ "$answer" != 继续 ]; then
    docker compose up -d app backup
    die "取消了，库没动，应用已经重新启动"
  fi
fi

step "删掉现在的库，重建一个空的"
docker compose up -d --wait postgres
docker compose exec -T postgres psql -U scm -d postgres -v ON_ERROR_STOP=1 -q \
  -c 'DROP DATABASE IF EXISTS scm WITH (FORCE)' -c 'CREATE DATABASE scm OWNER scm' < /dev/null

step "恢复（数据多的话要几分钟）"
# --single-transaction：要么全部恢复，要么什么都不留，不会恢复一半
if ! in_backup_container pg_restore --no-owner --exit-on-error --single-transaction --dbname=scm "/restore-src/$src_name"; then
  die "恢复失败，上面是 pg_restore 的报错。现在库是空的，应用没有启动。
恢复前的数据在 backups/pre-restore/ 里最新的文件，可以用它再执行一次 restore.sh 恢复回去"
fi

step "起应用"
# Redis 里的登录状态、字典缓存还是恢复前的，清掉。Redis 不落盘，重启一下就清空了
docker compose restart redis
docker compose up -d app backup
version=$(env_get .env SCM_VERSION)
if ! wait_for_version "$version" 180; then
  docker compose logs --tail 100 app
  die "数据恢复好了，但应用 3 分钟内没起来，上面是最近 100 行日志，请发给我们"
fi

printf '%s restore 成功：从 %s 恢复\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$file" >> backups/backup.log
info "
恢复好了：数据回到了 $src_name 备份时的样子。告诉大家重新登录"
