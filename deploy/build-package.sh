#!/usr/bin/env bash
# 打交付包（RFC 002 §6）。在我们自己的 Mac 上执行，包放在 dist/（不进 git）：
#
#   deploy/build-package.sh                        升级包，版本号是今天的日期，如 2026.11.14
#   deploy/build-package.sh 2026.11.14-2           同一天第二次发版，版本号加 -2
#   deploy/build-package.sh --full                 完整包：多带 PostgreSQL、Redis 的镜像，第一次安装用
#   deploy/build-package.sh --arch arm64           给 ARM 服务器打（默认 amd64，也就是 x86_64 服务器）
#
# 开始前：Docker Desktop 在跑；本机开发库起着（docker compose -f dev/compose.yml up -d，导出接口文档要起一次应用）；
# 工作区是干净的（打的是 HEAD，没提交的改动不会进包，干净才不会搞混）。
#
# 步骤：导出 HEAD → mvn verify（跑测试）→ pnpm build → 起一次应用导出 openapi.json → 按服务器的 CPU 架构打镜像 →
# docker save → 连同 deploy/package/ 打成 dist/scm-<版本>[-full].tar.gz，附 sha256 校验值。
# 这个脚本在 Mac 自带的 bash 3.2 上跑，不用 bash 4 才有的写法。
set -euo pipefail

die()  { printf '\n出错了：%s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }

VERSION=''
FULL=0
ARCH=amd64
while [ $# -gt 0 ]; do
  case $1 in
    --full) FULL=1 ;;
    --arch)
      if [ $# -lt 2 ]; then die "--arch 后面要写 amd64 或 arm64"; fi
      ARCH=$2
      shift ;;
    --arch=*) ARCH=${1#--arch=} ;;
    -h | --help) sed -n '2,14p' "$0"; exit 0 ;;
    -*) die "不认识的参数：$1（deploy/build-package.sh --help 看用法）" ;;
    *)
      if [ -n "$VERSION" ]; then die "版本号只能写一个"; fi
      VERSION=$1 ;;
  esac
  shift
done
VERSION=${VERSION:-$(date +%Y.%m.%d)}
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]{4}\.[0-9]{2}\.[0-9]{2}(-[0-9]+)?$'; then
  die "版本号要写成 2026.11.14 或 2026.11.14-2"
fi
case $ARCH in
  amd64 | arm64) ;;
  *) die "--arch 只能是 amd64 或 arm64" ;;
esac

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
NAME=scm-$VERSION
if [ "$FULL" = 1 ]; then FILE=$NAME-full.tar.gz; else FILE=$NAME.tar.gz; fi
OPENAPI_DB=scm_package_openapi     # 导出接口文档时应用连的临时库，在本机开发库的 PostgreSQL 里，用完删掉
OPENAPI_PORT=18099
dev_compose() { docker compose -f "$ROOT/dev/compose.yml" "$@"; }

step "检查"
if [ -e "dist/$FILE" ]; then
  die "dist/$FILE 已经有了。同一天再发一次，版本号加 -2：deploy/build-package.sh $VERSION-2"
fi
for cmd in docker mvn java git curl python3; do
  if ! command -v "$cmd" > /dev/null 2>&1; then die "没有 $cmd 命令"; fi
done
if ! docker info > /dev/null 2>&1; then die "Docker 没在运行：先启动 Docker Desktop"; fi
if ! docker buildx version > /dev/null 2>&1; then die "没有 docker buildx"; fi
if ! command -v pnpm > /dev/null 2>&1; then
  # nvm 装的 node 不在非交互 shell 的 PATH 里，找一个装了 pnpm 的
  for d in "$HOME"/.nvm/versions/node/*/bin; do
    if [ -x "$d/pnpm" ]; then PATH=$d:$PATH; fi
  done
  if ! command -v pnpm > /dev/null 2>&1; then die "没有 pnpm"; fi
fi
if [ -n "$(git status --porcelain)" ]; then
  die "工作区有没提交的改动。包是从 HEAD 打的，先提交或者 git stash"
fi
running=$(dev_compose ps --status running --services 2> /dev/null || true)
case $running in
  *postgres*) ;;
  *) die "本机开发库没起：docker compose -f dev/compose.yml up -d（导出接口文档要起一次应用）" ;;
esac
case $running in
  *redis*) ;;
  *) die "本机开发用的 Redis 没起：docker compose -f dev/compose.yml up -d" ;;
esac
if lsof -nP -iTCP:$OPENAPI_PORT -sTCP:LISTEN > /dev/null 2>&1; then
  die "端口 $OPENAPI_PORT 被占了，导出接口文档要用它"
fi
COMMIT=$(git rev-parse --short HEAD)
KIND=升级包
if [ "$FULL" = 1 ]; then KIND=完整包; fi
echo "  ${KIND}，版本 ${VERSION}，linux/${ARCH}，提交 $COMMIT"

BUILD=$(mktemp -d "${TMPDIR:-/tmp}/scm-package.XXXXXX")
APP_PID=''
cleanup() {
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2> /dev/null || true
    wait "$APP_PID" 2> /dev/null || true
  fi
  dev_compose exec -T postgres psql -U scm -d scm -q -c "DROP DATABASE IF EXISTS $OPENAPI_DB WITH (FORCE)" > /dev/null 2>&1 || true
  dev_compose exec -T redis redis-cli -n 15 FLUSHDB > /dev/null 2>&1 || true
  rm -rf "$BUILD"
}
trap cleanup EXIT

step "导出 HEAD"
mkdir "$BUILD/src"
git archive HEAD | tar -x -C "$BUILD/src"

step "后端：mvn verify"
# ScmApplicationTests 要连开发库 scm（配置在 application-local.yaml，不进 git，导出的代码里没有），先排除。
# RFC 001 把它改成连 scm_test 的集成测试以后，去掉这个排除
(cd "$BUILD/src" && mvn -B -q verify -Drelease.version="$VERSION" \
  -Dtest='!ScmApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false)
set -- "$BUILD"/src/target/scm-*.jar
JAR=$1
if [ ! -f "$JAR" ]; then die "没找到打出来的 jar"; fi

step "前端：pnpm build"
(cd "$BUILD/src/web" && pnpm install --frozen-lockfile --silent && pnpm build)

step "起一次应用，导出接口文档 openapi.json（生产环境关了 Swagger，接口文档随包给）"
dev_compose exec -T postgres psql -U scm -d scm -q -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS $OPENAPI_DB WITH (FORCE)" -c "CREATE DATABASE $OPENAPI_DB" < /dev/null
mkdir "$BUILD/uploads"
# 不用 prod 配置（它关了接口文档）；连临时库、Redis 用 15 号库，开发库里的数据一点不碰。密钥随手生成，用完就扔
(cd "$BUILD" && exec env SPRING_PROFILES_ACTIVE=openapi \
  DB_URL="jdbc:postgresql://127.0.0.1:5433/$OPENAPI_DB" DB_USER=scm DB_PASSWORD= \
  REDIS_HOST=127.0.0.1 REDIS_PORT=6380 REDIS_PASSWORD= REDIS_DB=15 \
  JWT_SECRET="$(head -c 48 /dev/urandom | base64)" INIT_ADMIN_PASSWORD="$(head -c 12 /dev/urandom | base64)" \
  UPLOAD_DIR="$BUILD/uploads" BACKUP_DIR= \
  java -jar "$JAR" --server.port=$OPENAPI_PORT) > "$BUILD/openapi-app.log" 2>&1 &
APP_PID=$!
tries=0
until curl --noproxy '*' -fsS -o "$BUILD/openapi.json" "http://127.0.0.1:$OPENAPI_PORT/v3/api-docs" 2> /dev/null; do
  tries=$((tries + 1))
  if [ $tries -ge 90 ] || ! kill -0 "$APP_PID" 2> /dev/null; then
    tail -n 50 "$BUILD/openapi-app.log" >&2
    die "应用 3 分钟内没起来，导不出 openapi.json。上面是应用日志的最后 50 行"
  fi
  sleep 2
done
kill "$APP_PID"
wait "$APP_PID" 2> /dev/null || true
APP_PID=''
# 去掉 servers（里面是这次临时起的 127.0.0.1 地址，对拿到文档的人没用），排一下版，方便直接看
python3 - "$BUILD/openapi.json" << 'PY' || die "导出来的 openapi.json 不对"
import json, sys
path = sys.argv[1]
with open(path, encoding='utf-8') as f:
    doc = json.load(f)
if 'openapi' not in doc:
    sys.exit(1)
doc.pop('servers', None)
with open(path, 'w', encoding='utf-8') as f:
    json.dump(doc, f, ensure_ascii=False, indent=2)
    f.write('\n')
PY
echo "  导出了 $(wc -c < "$BUILD/openapi.json" | tr -d ' ') 字节"

step "打镜像（linux/${ARCH}；和本机 CPU 不一样时 Docker 要模拟，慢一点）"
mkdir -p "$BUILD/ctx/app" "$BUILD/ctx/web"
cp "$JAR" "$BUILD/ctx/app/app.jar"
cp -R "$BUILD/src/web/dist" "$BUILD/ctx/web/dist"
# 不带 provenance、sbom：带了以后镜像多出几层清单，旧版本的 docker load 不认
docker buildx build --platform "linux/$ARCH" --provenance=false --sbom=false --load \
  -f "$BUILD/src/deploy/app.Dockerfile" -t "scm-app:$VERSION" "$BUILD/ctx/app"
docker buildx build --platform "linux/$ARCH" --provenance=false --sbom=false --load \
  -f "$BUILD/src/deploy/web.Dockerfile" -t "scm-web:$VERSION" "$BUILD/ctx/web"

step "组装安装包"
PKG=$BUILD/pkg/$NAME
mkdir -p "$PKG"
cp -R "$BUILD/src/deploy/package/." "$PKG/"
mkdir -p "$PKG/images" "$PKG/docs"
mv "$PKG/部署手册.md" "$PKG/docs/"
cp "$BUILD/openapi.json" "$PKG/docs/openapi.json"
printf '%s\n' "$VERSION" > "$PKG/VERSION"
printf '%s\n' "$ARCH" > "$PKG/ARCH"
chmod 755 "$PKG"/scripts/*.sh
docker save --platform "linux/$ARCH" -o "$PKG/images/scm-app.tar" "scm-app:$VERSION"
docker save --platform "linux/$ARCH" -o "$PKG/images/scm-web.tar" "scm-web:$VERSION"
if [ "$FULL" = 1 ]; then
  for img in postgres:16 redis:7; do
    echo "  拉 ${img}（linux/${ARCH}）"
    docker pull -q --platform "linux/$ARCH" "$img" > /dev/null
    docker save --platform "linux/$ARCH" -o "$PKG/images/$(echo "$img" | tr ':' '-').tar" "$img"
  done
  case $(uname -m) in arm64 | aarch64) NATIVE=arm64 ;; *) NATIVE=amd64 ;; esac
  if [ "$ARCH" != "$NATIVE" ]; then
    # Docker 没开 containerd 镜像存储时，同一个标签只能指向一个架构：拉回本机架构的，免得本机开发库下次用模拟的镜像起
    docker pull -q postgres:16 > /dev/null
    docker pull -q redis:7 > /dev/null
  fi
fi

step "打包"
mkdir -p "$ROOT/dist"
if tar --version 2> /dev/null | grep 'GNU tar' > /dev/null; then   # 不用 grep -q：它找到就退出，tar 会被 SIGPIPE，pipefail 下整句算失败
  tar -C "$BUILD/pkg" --owner=0 --group=0 --numeric-owner -czf "$ROOT/dist/$FILE" "$NAME"
else
  # Mac 自带的 bsdtar：属主写成 root，不带 Mac 的扩展属性（Linux 上解压会报一堆警告）
  COPYFILE_DISABLE=1 tar -C "$BUILD/pkg" --uid 0 --gid 0 --uname root --gname root \
    --no-xattrs --no-acls --no-fflags --no-mac-metadata -czf "$ROOT/dist/$FILE" "$NAME"
fi
(cd "$ROOT/dist" && if command -v sha256sum > /dev/null 2>&1; then sha256sum "$FILE"; else shasum -a 256 "$FILE"; fi > "$FILE.sha256")

cat <<EOF

打好了：dist/${FILE}（$(du -h "$ROOT/dist/$FILE" | cut -f1)）
  版本 ${VERSION}，linux/${ARCH}，提交 $COMMIT
  校验值 dist/$FILE.sha256，和包一起发给部署方

发出去以后打个标签，以后好找这个版本的代码：
  git tag v$VERSION $COMMIT && git push origin v$VERSION
本机还留着 scm-app:${VERSION}、scm-web:$VERSION 两个镜像（本机测安装、升级用），不要了就删：
  docker image rm scm-app:$VERSION scm-web:$VERSION
EOF
