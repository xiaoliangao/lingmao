# 安装、升级、恢复这些脚本共用的函数：由它们 source 进来，不单独执行。
# 调用方先 set -euo pipefail；用到 TARGET（安装目录）、PKG_DIR（解压出来的包目录）、PKG_VERSION 的函数，调用前要先设好。
#
# set -e 下容易踩的坑，这几个脚本都照这样写：
#   - 不写 `[ 条件 ] && 动作`，写 if：条件不成立时整句返回 1，放在函数最后一行会让调用方直接退出
#   - 可能"什么都没找到"的 grep 后面加 || true：pipefail 下 grep 没找到也算失败
#   - 管道里不用 grep -q：它找到第一行就退出，前面的命令再写就被 SIGPIPE 杀掉，pipefail 下整句算失败，结果反了
#   - 读 .env 只用 env_get 取需要的那一项：.env 里有密钥，不 source、不整个打印

# .env 是这些配置的唯一来源。docker compose 会优先用 shell 里的同名环境变量，先清掉，免得被 root 的环境悄悄覆盖
unset SCM_VERSION SCM_HTTP_PORT SCM_HTTPS_PORT SCM_WEB_MODE POSTGRES_PASSWORD REDIS_PASSWORD JWT_SECRET \
      FILE_SIGN_KEY INIT_ADMIN_PASSWORD BACKUP_TIME BACKUP_KEEP_DAYS COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES \
      ASSISTANT_DB_PASSWORD ASSISTANT_LLM_BASE_URL ASSISTANT_LLM_API_KEY ASSISTANT_LLM_MODEL ASSISTANT_LLM_MODE \
      ASSISTANT_LLM_STREAM_USAGE ASSISTANT_USER_DAILY_REQUESTS ASSISTANT_DAILY_TOKENS

# 脚本在服务器上写的时间（backup.log、屏幕上显示的备份文件时间）按北京时间，和 backup 容器里写的一致。
# 不设的话按服务器的时区：云服务器常常是 UTC，backup.log 里两种时间混在一起（2c 在 Mac 上测出来的）
if [ -e /usr/share/zoneinfo/Asia/Shanghai ]; then
  export TZ=Asia/Shanghai
else
  export TZ=CST-8
fi

APP_UID=10001        # app 镜像里跑应用的用户（deploy/app.Dockerfile），上传和日志目录要交给它
MIN_DOCKER=24.0
MIN_COMPOSE=2.20

info() { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
warn() { printf '注意：%s\n' "$*" >&2; }
die()  { printf '\n出错了：%s\n' "$*" >&2; exit 1; }

# 我们自己的 Mac 上也能装（Docker Desktop），当上线前的试用环境：
# Docker Desktop 按当前用户跑，sudo 了反而连不上；挂进容器的目录不管属主是谁容器都能写；Mac 上没有 flock、ss
is_mac() { [ "$(uname -s)" = Darwin ]; }

require_root() {
  if is_mac; then
    return 0
  fi
  if [ "$(id -u)" -ne 0 ]; then
    die "要用 root 执行：在命令前面加 sudo"
  fi
}

# version_at_least <a> <b>：a >= b。版本号按数字逐段比较（2026.11.14-2 比 2026.11.14 新，24.0.7 比 24.0 新）
version_at_least() {
  [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | sed -n 1p)" = "$2" ]
}

host_arch() {
  case "$(uname -m)" in
    x86_64 | amd64) echo amd64 ;;
    aarch64 | arm64) echo arm64 ;;
    *) uname -m ;;
  esac
}

check_docker() {
  local server compose
  if ! command -v docker >/dev/null 2>&1; then
    die "没找到 docker 命令。先照部署手册第 1 节装 Docker"
  fi
  server=$(docker version --format '{{.Server.Version}}' 2>/dev/null) || die "连不上 Docker 服务。先启动它：systemctl enable --now docker"
  if ! version_at_least "$server" "$MIN_DOCKER"; then
    die "Docker 版本是 ${server}，要 $MIN_DOCKER 以上。照部署手册第 1 节升级"
  fi
  compose=$(docker compose version --short 2>/dev/null) || die "没有 docker compose 插件。照部署手册第 1 节装 docker-compose-plugin"
  compose=${compose#v}
  if ! version_at_least "$compose" "$MIN_COMPOSE"; then
    die "docker compose 版本是 ${compose}，要 $MIN_COMPOSE 以上。照部署手册第 1 节升级"
  fi
}

# 内存、硬盘不够只警告，不拦着：试用时可能就是用小机器
check_resources() {
  local mem_kb free_kb
  mem_kb=$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo 2> /dev/null || true)
  if [ -n "$mem_kb" ] && [ "$mem_kb" -lt 3670016 ]; then
    warn "内存只有 $((mem_kb / 1024)) MB，建议 4G 以上。小了的话数据一多会变慢，严重时应用会被系统杀掉"
  fi
  free_kb=$(df -Pk "$TARGET" 2> /dev/null | awk 'NR == 2 { print $4 }' || true)
  if [ -n "$free_kb" ] && [ "$free_kb" -lt 20971520 ]; then
    warn "$TARGET 所在的硬盘只剩 $((free_kb / 1024 / 1024)) GB，建议 20G 以上：备份、上传的图片都存在这里"
  fi
}

# env_get <文件> <项>：输出某一项的值，没有这一项时输出空。
# 和 docker compose 读 .env 的规则一致：同一项写了多次以最后一次为准；去掉两边的引号；不带引号时 " #" 后面是注释
env_get() {
  local line value
  line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?$2=" "$1" 2>/dev/null | tail -n 1 || true)
  value=${line#*=}
  value=${value%$'\r'}
  value=${value#"${value%%[![:space:]]*}"}
  case $value in
    \"*) value=${value#\"}; value=${value%%\"*} ;;
    \'*) value=${value#\'}; value=${value%%\'*} ;;
    *)   value=${value%%[[:space:]]#*}
         value=${value%"${value##*[![:space:]]}"} ;;
  esac
  printf '%s' "$value"
}

# env_has <文件> <项>：有没有这一项（值空着也算有）
env_has() {
  grep -qE "^[[:space:]]*(export[[:space:]]+)?$2=" "$1" 2>/dev/null
}

# env_set <文件> <项> <值>：改某一项，没有就加在末尾。先写临时文件再改名：中途出错不会留下半个 .env
env_set() {
  local tmp
  tmp=$(mktemp "$1.XXXXXX")
  KEY=$2 VALUE=$3 awk '
    $0 ~ "^[ \t]*(export[ \t]+)?" ENVIRON["KEY"] "=" {
      if (!done) print ENVIRON["KEY"] "=" ENVIRON["VALUE"]
      done = 1
      next
    }
    { print }
    END { if (!done) print ENVIRON["KEY"] "=" ENVIRON["VALUE"] }
  ' "$1" > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$1"
}

random_hex()    { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }
random_base64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }

# random_password <长度>：只用字母和数字，去掉了容易看错的 0 O 1 l I，照着屏幕输入不会输错
random_password() {
  local pw=''
  while [ "${#pw}" -lt "$1" ]; do
    pw=$pw$(head -c 64 /dev/urandom | LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9')
  done
  printf '%s' "${pw:0:$1}"
}

# 新建 .env 时每一项的值：密钥随机生成，版本号是包的版本，其他照 env.example 里写的默认值
initial_value() {
  case $1 in
    SCM_VERSION) printf '%s' "$PKG_VERSION" ;;
    POSTGRES_PASSWORD | REDIS_PASSWORD | ASSISTANT_DB_PASSWORD) random_hex 16 ;;
    JWT_SECRET) random_base64 48 ;;                  # 应用要求 Base64、解码后至少 32 字节
    FILE_SIGN_KEY) random_hex 32 ;;                  # 只有字母和数字：原样写进 Nginx 配置，不用转义
    INIT_ADMIN_PASSWORD) random_password 16 ;;
    *) printf '%s' "$2" ;;
  esac
}

# create_env <env.example> <.env>：照模板生成 .env，模板里的说明一起抄过去，部署方打开 .env 就能看到每一项是干什么的
create_env() {
  local tmp line key
  tmp=$(mktemp "$2.XXXXXX")
  while IFS= read -r line || [ -n "$line" ]; do
    case $line in
      [A-Z]*=*)
        key=${line%%=*}
        printf '%s=%s\n' "$key" "$(initial_value "$key" "${line#*=}")" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done < "$1" > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$2"
}

# merge_env <env.example> <.env>：升级时，新版本的模板里多出来的项加到 .env 末尾；已有的项一个都不动
merge_env() {
  local line key added=''
  while IFS= read -r line || [ -n "$line" ]; do
    case $line in
      [A-Z]*=*)
        key=${line%%=*}
        if ! grep -qE "^[[:space:]]*(export[[:space:]]+)?$key=" "$2"; then
          if [ -z "$added" ]; then
            printf '\n# 版本 %s 新加的配置项，说明见 env.example\n' "$PKG_VERSION" >> "$2"
          fi
          printf '%s=%s\n' "$key" "$(initial_value "$key" "${line#*=}")" >> "$2"
          added="$added $key"
        fi ;;
    esac
  done < "$1"
  if [ -n "$added" ]; then
    info "  .env 里加了新的配置项：${added# }"
  fi
}

# 包里的文件复制到安装目录（RFC 002 §3 "升级时会覆盖的"）。
# 部署方自己的东西不碰：.env、compose.override.yml、certs/、data/、backups/、logs/，以及已经有了的 nginx/trusted-proxies.conf。
# 用 install 复制：它先删掉旧文件再写新文件，正在执行的旧脚本、容器里正在跑的 backup-loop.sh 不会读到写了一半的新内容
copy_package_files() {
  local f
  if [ "$PKG_DIR" -ef "$TARGET" ]; then
    return 0     # 就在安装目录里执行的（重复执行 install.sh），没有要复制的
  fi
  install -m 644 "$PKG_DIR"/VERSION "$PKG_DIR"/ARCH "$PKG_DIR"/compose.yml "$PKG_DIR"/web-http.yml \
    "$PKG_DIR"/web-https.yml "$PKG_DIR"/env.example "$TARGET"/
  install -d -m 755 "$TARGET"/nginx "$TARGET"/scripts "$TARGET"/docs
  for f in "$PKG_DIR"/nginx/*; do
    if [ "${f##*/}" = trusted-proxies.conf ] && [ -e "$TARGET"/nginx/trusted-proxies.conf ]; then
      continue
    fi
    install -m 644 "$f" "$TARGET"/nginx/
  done
  install -m 755 "$PKG_DIR"/scripts/*.sh "$TARGET"/scripts/
  install -m 644 "$PKG_DIR"/docs/* "$TARGET"/docs/
}

# make_dir <权限> <目录>...：不存在才建。已经有的不改权限：数据库目录的属主和权限由 postgres 容器自己管，改了它会拒绝启动
make_dir() {
  local mode=$1 d
  shift
  for d in "$@"; do
    if [ ! -d "$d" ]; then
      install -d -m "$mode" "$d"
    fi
  done
}

ensure_dirs() {
  # data/exports 是后台导出生成的文件，只挂给应用（下载时应用重新查权限），不挂给 Nginx
  make_dir 755 "$TARGET"/data "$TARGET"/data/postgres "$TARGET"/data/uploads "$TARGET"/data/exports "$TARGET"/logs \
    "$TARGET"/backups "$TARGET"/backups/daily "$TARGET"/backups/pre-upgrade "$TARGET"/backups/pre-restore
  make_dir 700 "$TARGET"/certs
  # 应用不用 root 跑，上传、导出和日志目录要交给它才写得进去
  if ! is_mac; then
    chown "$APP_UID:$APP_UID" "$TARGET"/data/uploads "$TARGET"/data/exports "$TARGET"/logs
  fi
}

# 启动之前检查 .env 里容易写错的几项：写错了容器会起不来或者反复重启，不如现在就说清楚
check_env() {
  local mode backup_time
  mode=$(env_get "$TARGET"/.env SCM_WEB_MODE)
  case ${mode:-http} in
    http) ;;
    https)
      if [ ! -s "$TARGET"/certs/fullchain.pem ] || [ ! -s "$TARGET"/certs/privkey.pem ]; then
        die "SCM_WEB_MODE=https，但 $TARGET/certs/ 里没有 fullchain.pem 和 privkey.pem。见部署手册第 4 节"
      fi ;;
    *) die "SCM_WEB_MODE 只能是 http 或 https，.env 里现在是 \"${mode}\"" ;;
  esac
  backup_time=$(env_get "$TARGET"/.env BACKUP_TIME)
  case ${backup_time:-02:30} in
    [01][0-9]:[0-5][0-9] | 2[0-3]:[0-5][0-9]) ;;
    *) die "BACKUP_TIME 要写成 02:30 这样的 24 小时制时间，.env 里现在是 \"${backup_time}\"" ;;
  esac
  local profiles
  profiles=$(env_get "$TARGET"/.env COMPOSE_PROFILES)
  case $profiles in
    '' | assistant) ;;
    *) die "COMPOSE_PROFILES 只能空着（不起助手）或者写 assistant，.env 里现在是 \"${profiles}\"。见部署手册第 10 节" ;;
  esac
  # 没有这一项的不管：升级前检查时旧的 .env 还没有，升级脚本随后补上。有这一项、值却空着的要报：
  # 升级脚本不会替它补（merge_env 只补没有的项），放过去就到建库账号那一步才停，那时文件已经换成新版本的了
  if env_has "$TARGET"/.env ASSISTANT_DB_PASSWORD; then
    check_assistant_password
  fi
}

# 助手库账号的密码：要有，只能是字母、数字、- 和 _，至少 16 位（安装脚本生成的是 32 位十六进制）。
# 它原样写进数据库的 ALTER ROLE 语句，所以字符要管住。恢复脚本在动库之前就查：恢复到一半才发现不对，应用就起不来了
check_assistant_password() {
  local problem
  problem=$(assistant_password_problem)
  if [ -n "$problem" ]; then
    die "$problem"
  fi
}

# 密码有什么不对，输出一句话；没问题输出空
assistant_password_problem() {
  local pw re='^[A-Za-z0-9_-]{16,}$'
  if ! env_has "$TARGET"/.env ASSISTANT_DB_PASSWORD; then
    printf '%s' ".env 里没有 ASSISTANT_DB_PASSWORD（升级脚本会自动补上；手动改过 .env 的话，照 env.example 补一行，值用 32 位随机的字母数字，比如 openssl rand -hex 16 的输出）"
    return 0
  fi
  pw=$(env_get "$TARGET"/.env ASSISTANT_DB_PASSWORD)
  if [ -z "$pw" ]; then
    printf '%s' ".env 里 ASSISTANT_DB_PASSWORD 空着：填 32 位随机的字母数字（比如 openssl rand -hex 16 的输出），不要空着"
  elif ! [[ $pw =~ $re ]]; then
    printf '%s' "ASSISTANT_DB_PASSWORD 只能用字母、数字、- 和 _，至少 16 位"
  fi
}

# 助手开着没有（.env 里 COMPOSE_PROFILES=assistant）
assistant_enabled() {
  [ "$(env_get "$TARGET"/.env COMPOSE_PROFILES)" = assistant ]
}

# 智能助手的库账号和 schema（RFC 017 §3.4）：账号 scm_assistant 只拥有 assistant 这个 schema，app 的表没给它授权。
# 安装、升级、恢复都执行一遍，重跑不出错：没有账号就建；密码按 .env 的 ASSISTANT_DB_PASSWORD 重设（.env 改过也跟上）；
# schema 和里面的东西都归这个账号——pg_restore --no-owner 恢复出来的归 scm，不改回来助手连上了也动不了自己的表。
# 不光是表：视图、单独建的序列、函数、类型以后的迁移加了也一样交给它（跟着表的 identity 序列随表走，不用单独交）。
# 不管开没开助手都建好：以后要开只改 .env。要求 postgres 已经起来。
# 出错时返回 1、不直接退出：升级脚本要接着告诉人怎么回到旧版本
ensure_assistant_db() {
  ensure_assistant_role || return 1
  assistant_psql <<'SQL'
CREATE SCHEMA IF NOT EXISTS assistant AUTHORIZATION scm_assistant;
ALTER SCHEMA assistant OWNER TO scm_assistant;
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT c.oid::regclass AS name,
                  CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'S' THEN 'SEQUENCE'
                                 WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END AS kind
             FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'assistant' AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
              AND c.relowner <> 'scm_assistant'::regrole
              AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
                                                      AND d.objid = c.oid AND d.deptype IN ('a', 'i')))
  LOOP
    EXECUTE format('ALTER %s %s OWNER TO scm_assistant', r.kind, r.name);
  END LOOP;
  FOR r IN SELECT p.oid::regprocedure AS name, CASE p.prokind WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' ELSE 'FUNCTION' END AS kind
             FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'assistant' AND p.proowner <> 'scm_assistant'::regrole
  LOOP
    EXECUTE format('ALTER %s %s OWNER TO scm_assistant', r.kind, r.name);
  END LOOP;
  -- 枚举、域、单独建的复合类型（表自带的行类型、数组类型跟着走）
  FOR r IN SELECT t.oid::regtype AS name, CASE t.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END AS kind
             FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = 'assistant' AND t.typowner <> 'scm_assistant'::regrole
              AND (t.typtype IN ('e', 'd') OR (t.typtype = 'c' AND (SELECT relkind FROM pg_class WHERE oid = t.typrelid) = 'c'))
  LOOP
    EXECUTE format('ALTER %s %s OWNER TO scm_assistant', r.kind, r.name);
  END LOOP;
END $$;
SQL
}

# 只建账号、设密码（不碰 schema）：恢复脚本在 pg_restore 之前调。备份里以后要是有给这个账号的 GRANT，
# 恢复到还没有这个账号的服务器上，pg_restore 会因为找不到账号整个失败
ensure_assistant_role() {
  assistant_psql <<'SQL'
SELECT 'CREATE ROLE scm_assistant LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scm_assistant') \gexec
SELECT format('ALTER ROLE scm_assistant LOGIN PASSWORD %L', :'pw') \gexec
ALTER ROLE scm_assistant SET search_path = assistant;
SQL
}

# 用 scm 在库里执行标准输入的 SQL，里面用 :'pw' 取助手账号的密码。
# 密码从标准输入交给 psql，不出现在命令行上（ps 看得到命令行），数据库日志也不记语句（compose.yml 里 log_statement=none）；
# -X 不读 .psqlrc；报错原样打出来但把密码换成 ***（psql 报语法错时会带上那一行语句）
assistant_psql() {
  local pw problem err
  problem=$(assistant_password_problem)
  if [ -n "$problem" ]; then
    printf '%s\n' "$problem" >&2
    return 1
  fi
  pw=$(env_get "$TARGET"/.env ASSISTANT_DB_PASSWORD)
  if ! err=$( { printf '%s\n' "\\set pw '$pw'"; cat; } \
      | docker compose exec -T postgres psql -X -U scm -d scm -v ON_ERROR_STOP=1 -q 2>&1 > /dev/null); then
    printf '%s\n' "${err//"$pw"/***}" >&2
    return 1
  fi
}

# 开着助手时从 Nginx 走一遍 /assistant/health。只警告：应用已经好了，助手的问题单独查（最多等 60 秒，它要先执行自己的迁移）
check_assistant() {
  local waited=0 url=http://127.0.0.1/assistant/health got=''
  if ! assistant_enabled; then
    return 0
  fi
  if [ "$(env_get "$TARGET"/.env SCM_WEB_MODE)" = https ]; then
    url=https://127.0.0.1/assistant/health
  fi
  while [ "$waited" -lt 60 ]; do
    got=$(docker compose exec -T web curl -fsSk --max-time 5 "$url" 2>/dev/null < /dev/null || true)
    case $got in
      *'"status":"UP"'*)
        case $got in
          *'"model":"none"'*) warn "智能助手起来了，但还没配模型（.env 里的 ASSISTANT_LLM_*），问话会回\"还没有配置模型\"" ;;
          *) info "  智能助手也正常" ;;
        esac
        return 0 ;;
    esac
    sleep 5
    waited=$((waited + 5))
  done
  warn "开着智能助手，但它 1 分钟内没起来。看一下：docker compose logs --tail 50 assistant"
}

# 这台机器上已经有一套装在别的目录、compose 项目名一样（默认都是 scm）时停下来：接着 up 的话，
# 那一套的容器会被换成这个目录的，库也换成这里的空库，看起来像数据没了（那边的文件其实都还在）。
# 真要在一台机器上装两套（比如演练换服务器），新目录的 .env 里先写一行 COMPOSE_PROJECT_NAME=<别的名字>，端口也换一个
check_other_install() {
  local name dir
  name=$(env_get "$TARGET"/.env COMPOSE_PROJECT_NAME)
  name=${name:-scm}
  dir=$(docker ps -a --filter "label=com.docker.compose.project=$name" \
    --format '{{.Label "com.docker.compose.project.working_dir"}}' 2>/dev/null | sort -u | sed -n 1p || true)
  if [ -n "$dir" ] && ! [ "$dir" -ef "$TARGET" ]; then
    die "这台机器上已经在 $dir 装了一套（compose 项目名 ${name}）。
升级那一套用 upgrade.sh ${dir}，见部署手册第 6 节。确实要再装一套的话，先在 $TARGET/.env 里加一行 COMPOSE_PROJECT_NAME=<别的名字>，端口也换一个"
  fi
}

# 端口被别的程序占了就停下来。自己的 web 已经在跑（重复执行）时不查：占着端口的就是它
check_ports() {
  local spec port ports busy
  if [ -n "$(docker compose ps -q --status running web 2>/dev/null || true)" ]; then
    return 0
  fi
  if ! command -v ss >/dev/null 2>&1 && ! command -v lsof >/dev/null 2>&1; then
    warn "没有 ss 命令，跳过端口检查"
    return 0
  fi
  spec=$(env_get "$TARGET"/.env SCM_HTTP_PORT)
  ports=${spec:-80}
  if [ "$(env_get "$TARGET"/.env SCM_WEB_MODE)" = https ]; then
    spec=$(env_get "$TARGET"/.env SCM_HTTPS_PORT)
    ports="$ports ${spec:-443}"
  fi
  for spec in $ports; do
    port=${spec##*:}       # 也可能写成 127.0.0.1:8080
    if command -v ss >/dev/null 2>&1; then
      busy=$(ss -ltn "sport = :$port" | grep LISTEN || true)
    else
      busy=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
    fi
    if [ -n "$busy" ]; then
      die "端口 $port 已经被别的程序占了（ss -ltnp 'sport = :$port' 能看到是谁）。换一个：改 $TARGET/.env 里的端口，再执行一次"
    fi
  done
}

# 载入包里的镜像。安装目录里没有 images/（重复执行 install.sh 时），跳过，后面 require_images 会查镜像在不在
load_images() {
  local f
  if [ ! -d "$PKG_DIR"/images ]; then
    return 0
  fi
  for f in "$PKG_DIR"/images/*.tar; do
    if [ ! -e "$f" ]; then
      continue
    fi
    info "  载入 ${f##*/}"
    docker load -q -i "$f" >/dev/null || die "载入 ${f##*/} 失败。包可能没传完整，对一下 sha256 校验值（部署手册第 2 节）"
  done
}

require_images() {
  local img missing=''
  for img in "scm-app:$1" "scm-web:$1" "scm-assistant:$1" postgres:16 redis:7; do
    if ! docker image inspect "$img" >/dev/null 2>&1; then
      missing="$missing $img"
    fi
  done
  if [ -n "$missing" ]; then
    die "缺少镜像：${missing# }。第一次安装要用完整包（文件名带 -full）"
  fi
}

# wait_for_version <版本> <最多等几秒>：等应用起来、版本号对上。
# 数据库迁移在应用启动时执行，版本号对上了，说明迁移也做完了
wait_for_version() {
  local waited=0 got
  while [ "$waited" -lt "$2" ]; do
    got=$(docker compose exec -T app curl -fsS --max-time 5 http://127.0.0.1:8080/api/version 2>/dev/null < /dev/null \
      | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' || true)
    if [ "$got" = "$1" ]; then
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

# 再从 Nginx 走一遍，确认 web 也好了。只警告：应用已经起来了，Nginx 的问题单独查
check_through_nginx() {
  local url=http://127.0.0.1/api/version got
  if [ "$(env_get "$TARGET"/.env SCM_WEB_MODE)" = https ]; then
    url=https://127.0.0.1/api/version
  fi
  got=$(docker compose exec -T web curl -fsSk --max-time 5 "$url" 2>/dev/null < /dev/null || true)
  case $got in
    *"\"version\":\"$1\""*) info "  从 Nginx 访问也正常" ;;
    *) warn "应用起来了，但从 Nginx 访问不正常。看一下：docker compose logs --tail 50 web" ;;
  esac
}

# 每个应用镜像只留最近 3 个版本（回滚要用上一个），再加上正在用的
prune_old_images() {
  local repo tag
  for repo in scm-app scm-web scm-assistant; do
    while read -r tag; do
      if [ -n "$tag" ] && [ "$tag" != "$1" ]; then
        docker image rm "$repo:$tag" >/dev/null 2>&1 || true
      fi
    done < <(docker image ls "$repo" --format '{{.Tag}}' | grep -v '^<none>$' | sort -V -r | sed -n '4,$p' || true)
  done
  docker image prune -f --filter label=project=scm >/dev/null 2>&1 || true
}

# 同一时间只让一个安装、升级、恢复脚本跑：两个一起动数据库，结果说不清
take_lock() {
  if is_mac; then
    return 0     # 只有我们自己在用，不防
  fi
  exec 9> "$TARGET"/.scm.lock
  if ! flock -n 9; then
    die "另一个安装、升级或恢复脚本正在执行，等它结束再来"
  fi
}

# newest_dump <目录>：目录里最新的备份文件（文件名里带时间，按名字排在最后的就是最新的），没有时输出空
newest_dump() {
  local f newest=''
  for f in "$1"/scm-*.dump; do
    if [ -f "$f" ]; then
      newest=$f
    fi
  done
  printf '%s' "$newest"
}

abs_path() {
  printf '%s/%s' "$(cd "$(dirname "$1")" && pwd)" "$(basename "$1")"
}
