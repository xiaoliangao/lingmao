#!/usr/bin/env bash
# 升级。在新版本解压出来的包目录里执行：
#
#   sudo ./scripts/upgrade.sh              升级装在 /opt/scm 的系统
#   sudo ./scripts/upgrade.sh /data/scm    装在别的目录时
#
# 步骤（RFC 002 §9.2）：检查版本 → 载入新镜像 → 升级前备份，失败就停、什么都不改 → 覆盖包里的文件，.env 里的版本号改成新的 →
# 重建 app 和 web → 等新版本起来 → 清理旧镜像（留最近 3 个版本，回滚用）。
# 停机大约一分钟，这期间页面会提示"系统正在升级"。放在晚上或者没人用系统的时候。
set -euo pipefail

PKG_DIR=$(cd "$(dirname "$0")/.." && pwd)
. "$PKG_DIR"/scripts/lib.sh

TARGET=${1:-/opt/scm}
case $TARGET in
  /?*) TARGET=${TARGET%/} ;;
  *) die "安装目录要写绝对路径，比如 /opt/scm" ;;
esac
PKG_VERSION=$(cat "$PKG_DIR"/VERSION)
PKG_ARCH=$(cat "$PKG_DIR"/ARCH)

require_root
if [ ! -f "$TARGET"/.env ] || [ ! -f "$TARGET"/compose.yml ]; then
  die "$TARGET 下面没有装好的系统。第一次安装用 install.sh"
fi
if [ "$PKG_DIR" -ef "$TARGET" ]; then
  die "要在新版本的包解压出来的目录里执行，不是在安装目录里"
fi

step "检查"
check_docker
if [ "$(host_arch)" != "$PKG_ARCH" ]; then
  die "这个包是给 $PKG_ARCH 的服务器打的，这台服务器是 $(host_arch)。找我们要对应的包"
fi
cd "$TARGET"
take_lock
check_env           # .env 里有写错的，在动任何东西之前停下来
OLD=$(env_get .env SCM_VERSION)
NEW=$PKG_VERSION
if [ -z "$OLD" ]; then
  die "$TARGET/.env 里没有 SCM_VERSION，不知道现在是哪个版本"
fi
REAPPLY=''
if [ "$OLD" = "$NEW" ]; then
  REAPPLY=1
  info "  已经是 ${NEW}：把包里的文件重新应用一遍，不备份"
elif version_at_least "$OLD" "$NEW"; then
  die "现在的版本 $OLD 比这个包（${NEW}）还新。要退回旧版本，见部署手册第 7 节"
else
  info "  $OLD → $NEW"
fi

step "载入新版本的镜像"
load_images
require_images "$NEW"

if [ -z "$REAPPLY" ]; then
  step "升级前备份数据库"
  if ! bash "$TARGET"/scripts/backup.sh pre-upgrade "$OLD"; then
    die "升级前备份失败，所以没有升级，系统还是 ${OLD}。先看上面的报错（常见原因：硬盘满了）"
  fi
fi

step "更新文件"
copy_package_files
merge_env env.example .env
env_set .env SCM_VERSION "$NEW"
ensure_dirs

# 新版本没起来时告诉部署人员怎么回到旧版本（RFC 002 §9.3）
rollback_help() {
  local backup
  backup=$(newest_dump "$TARGET"/backups/pre-upgrade)
  cat >&2 <<EOF

$1

要先回到旧版本 ${OLD}：
    cd $TARGET
    sudo sed -i 's/^SCM_VERSION=.*/SCM_VERSION=$OLD/' .env
    sudo docker compose up -d

旧版本也起不来（新版本的数据库迁移出了错）时，用升级前的备份恢复，见部署手册第 7 节：
    sudo $TARGET/scripts/restore.sh ${backup:-<backups/pre-upgrade/ 下最新的文件>}
EOF
  exit 1
}

step "重启应用（大约一分钟，这期间页面会提示系统正在升级）"
if ! docker compose up -d --remove-orphans; then
  rollback_help "启动新版本出错了，上面是报错，请发给我们。"
fi
# app、web 换了镜像，会重建。backup 的配置没变不会重建，重启一下让它用上新的 backup-loop.sh；
# 同一个版本重新应用时 web 也不会重建，一起重启，用上新的 Nginx 配置
if [ -n "$REAPPLY" ]; then
  docker compose restart web backup
else
  docker compose restart backup
fi
info "  等新版本起来，最多 5 分钟 ..."
if ! wait_for_version "$NEW" 300; then
  docker compose logs --tail 100 app
  rollback_help "新版本 5 分钟内没起来，上面是最近 100 行日志，请发给我们。
日志里如果还在执行数据库迁移（数据多的时候会慢），再等等：cd $TARGET && sudo docker compose logs -f app"
fi
check_through_nginx "$NEW"

step "清理旧镜像（留最近 3 个版本，回滚用）"
prune_old_images "$NEW"

if [ -n "$REAPPLY" ]; then
  info "
完成：版本还是 ${NEW}，包里的文件重新应用了一遍"
else
  info "
升级好了：$OLD → ${NEW}。让大家刷新一下页面就是新版本"
fi
