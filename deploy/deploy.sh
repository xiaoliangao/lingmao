#!/usr/bin/env bash
# 发版：把当前提交（HEAD）打包，传到服务器，重启 app 和 web
#
# 用法（在 SCM 目录下）：./deploy/deploy.sh
# 服务器地址写在 deploy/deploy.local（不进 git，仓库是公开的）：
#   DEPLOY_HOST=root@<服务器IP>
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f deploy/deploy.local ] && source deploy/deploy.local
: "${DEPLOY_HOST:?在 deploy/deploy.local 里写一行 DEPLOY_HOST=root@<服务器IP>}"
REMOTE=/opt/lingmao

COMMIT=$(git rev-parse --short HEAD)
if [ -n "$(git status --porcelain)" ]; then
  echo "注意：工作区有没提交的改动，它们不会发布。这次只发 $COMMIT"
fi

# 1. 把 HEAD 导出到临时目录，在那里打包：没提交的改动、application-local.yaml（不在 git 里）都混不进去
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
git archive HEAD | tar -x -C "$BUILD"

echo "== 后端：打包并跑单元测试（ScmApplicationTests 要连真实数据库，跳过）"
if ! (cd "$BUILD" && mvn -q package -Dtest='!ScmApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false > "$BUILD/mvn.log" 2>&1); then
  tail -60 "$BUILD/mvn.log"
  echo "打包或测试失败，没有发布"
  exit 1
fi
cp "$BUILD"/target/scm-*.jar "$BUILD/app.jar"

echo "== 前端：类型检查并打包"
(cd "$BUILD/web" && pnpm install --frozen-lockfile --silent && pnpm build > /dev/null)
echo "$COMMIT" > "$BUILD/web/dist/version.txt"

echo "== 服务器准备（密钥、目录、生产库，已有的跳过）"
rsync -rlptz "$BUILD/deploy/server-init.sh" "$DEPLOY_HOST:$REMOTE/"
ssh "$DEPLOY_HOST" "bash $REMOTE/server-init.sh"

echo "== 上传"
# -rlptz：和 -a 一样，但不带本机的属主、属组（服务器上统一归 root）
sync_to() { rsync -rlptz "$@"; }
sync_to "$BUILD/deploy/docker-compose.app.yml" "$DEPLOY_HOST:$REMOTE/"
sync_to "$BUILD/deploy/Dockerfile" "$BUILD/app.jar" "$DEPLOY_HOST:$REMOTE/app/"
sync_to "$BUILD/deploy/nginx.conf" "$DEPLOY_HOST:$REMOTE/web/"
# --delay-updates：全部传完再一起换上；--delete-after：换完再删旧文件。传到一半时用户打开的仍是完整的旧版本
sync_to --delay-updates --delete-after "$BUILD/web/dist/" "$DEPLOY_HOST:$REMOTE/web/dist/"

echo "== 重建镜像、重启 app 和 web（--no-deps：不动 PostgreSQL、Redis）"
ssh "$DEPLOY_HOST" "cd $REMOTE && docker compose build --quiet app && docker compose up -d --no-deps app && docker compose up -d --no-deps --force-recreate web && docker image prune -f --filter label=project=lingmao > /dev/null"

echo "== 等后端起来"
ssh "$DEPLOY_HOST" "cd $REMOTE"' && port=$(grep "^LINGMAO_WEB_PORT=" .env | cut -d= -f2) && for i in $(seq 1 45); do
  if curl -s "http://localhost:$port/api/auth/me" | grep -q "\"code\":401"; then echo "已上线：$(curl -s http://localhost:$port/version.txt)"; exit 0; fi
  sleep 2
done
echo "90 秒没起来，看日志：docker compose logs --tail 100 app"; exit 1'
