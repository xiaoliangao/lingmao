#!/usr/bin/env bash
# 第一次安装。在解压出来的包目录里执行：
#
#   sudo ./scripts/install.sh              装到 /opt/scm
#   sudo ./scripts/install.sh /data/scm    装到别的目录（写绝对路径）
#
# 做什么：检查 Docker 和服务器 → 把包里的文件复制到安装目录 → 生成 .env（密钥随机生成）→ 建目录 → 载入镜像 → 启动 → 等应用起来。
# 可以重复执行：.env、证书、数据、nginx/trusted-proxies.conf 已经有的都不动。已经装了别的版本时不往下走，升级用 upgrade.sh。
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

step "检查服务器"
check_docker
if [ "$(host_arch)" != "$PKG_ARCH" ]; then
  die "这个包是给 $PKG_ARCH 的服务器打的，这台服务器是 $(host_arch)。找我们要对应的包"
fi
if [ -f "$TARGET"/.env ]; then
  installed=$(env_get "$TARGET"/.env SCM_VERSION)
  if [ -n "$installed" ] && [ "$installed" != "$PKG_VERSION" ]; then
    die "$TARGET 已经装了版本 ${installed}。换版本用 upgrade.sh，见部署手册第 6 节"
  fi
fi
install -d -m 755 "$TARGET"
cd "$TARGET"
take_lock
check_resources
info "  Docker、CPU 架构都没问题"

step "复制文件到 $TARGET"
copy_package_files
if [ -f .env ]; then
  merge_env env.example .env
  info "  .env 已经有了，原有的配置没动"
else
  create_env env.example .env
  info "  生成了 .env，数据库、Redis 的密码和登录密钥都是随机生成的"
fi
chmod 600 .env
ensure_dirs
check_env
check_ports

step "载入镜像（要一两分钟）"
load_images
require_images "$PKG_VERSION"

step "启动"
docker compose up -d --remove-orphans
info "  等应用起来。第一次启动要建表，最多等 3 分钟 ..."
if ! wait_for_version "$PKG_VERSION" 180; then
  docker compose ps
  docker compose logs --tail 100 app
  die "3 分钟内应用没起来，上面是最近 100 行日志。部署手册第 9 节有常见问题；解决不了就把这些日志发给我们"
fi
check_through_nginx "$PKG_VERSION"

if [ "$(env_get .env SCM_WEB_MODE)" = https ]; then
  url="https://<域名>/"
  port=$(env_get .env SCM_HTTPS_PORT)
  if [ -n "$port" ] && [ "$port" != 443 ]; then
    url="https://<域名>:$port/"
  fi
else
  url="http://<服务器 IP>/"
  port=$(env_get .env SCM_HTTP_PORT)
  port=${port##*:}
  if [ -n "$port" ] && [ "$port" != 80 ]; then
    url="http://<服务器 IP>:$port/"
  fi
fi

# 初始密码不打印：远程协助时屏幕是共享的，打印出来对方就看到了，录屏里也会留下
cat <<EOF

装好了：版本 ${PKG_VERSION}，在 $TARGET

  访问地址    $url
  管理员账号  admin
  初始密码    在 .env 里。远程协助的话，先停止屏幕共享，再执行：
                sudo grep INIT_ADMIN_PASSWORD $TARGET/.env
              登录以后马上在右上角的用户菜单里改掉

还有两件必须做的事，部署手册里有步骤：
  1. 域名和 HTTPS（第 4 节）：不用 HTTPS 的话，密码在网上是明文传的
  2. 异地备份（第 5 节）：每天的备份和数据在同一块硬盘上，硬盘坏了一起没
EOF
