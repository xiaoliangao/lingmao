#!/usr/bin/env bash
# 在服务器上执行，deploy.sh 每次发版前通过 ssh 调用它。可以重复执行：已经有的东西不会再建、不会覆盖
# 前提：/opt/lingmao 下已有 docker-compose.yml（PostgreSQL、Redis）和 .env，且 .env 里配了 LINGMAO_WEB_PORT
set -euo pipefail
cd /opt/lingmao

grep -q '^LINGMAO_WEB_PORT=' .env || { echo "先在 /opt/lingmao/.env 里加一行 LINGMAO_WEB_PORT=<对外端口>"; exit 1; }

# 1. 生产密钥：.env 里没有才生成。只写进 .env（chmod 600），不打印
add_secret() {
  if ! grep -q "^$1=" .env; then
    echo "$1=$2" >> .env
    echo "已生成 $1（在 /opt/lingmao/.env 里）"
  fi
}
add_secret LINGMAO_PROD_DB_PASSWORD "$(openssl rand -hex 16)"
add_secret LINGMAO_PROD_JWT_SECRET "$(openssl rand -base64 32)"
add_secret LINGMAO_PROD_ADMIN_PASSWORD "$(openssl rand -hex 8)"
chmod 600 .env

# 2. 在这个目录执行 docker compose 时，自动把应用部分也读进来
grep -q '^COMPOSE_FILE=' .env || echo "COMPOSE_FILE=docker-compose.yml:docker-compose.app.yml" >> .env

# 3. 目录。上传目录给容器里的 app 用户（uid 10001，见 Dockerfile）写
mkdir -p app web/dist uploads
chown 10001:10001 uploads

# 4. 生产库和用户：同一个 PostgreSQL 容器里另建一套，和开发库 lingmao 互不相通
# 以超级用户执行一条 SQL。容器里的本机连接免密码
psql_super() { docker exec lingmao-postgres psql -U postgres -v ON_ERROR_STOP=1 -qtA -c "$1"; }

if [ -z "$(psql_super "SELECT 1 FROM pg_roles WHERE rolname = 'lingmao_prod'")" ]; then
  pw=$(grep '^LINGMAO_PROD_DB_PASSWORD=' .env | cut -d= -f2-)
  # 密码通过 psql 变量传进去（:'pw' 会加上引号并转义），不拼在 SQL 字符串里
  docker exec -i lingmao-postgres psql -U postgres -v ON_ERROR_STOP=1 -q -v pw="$pw" <<'SQL'
CREATE ROLE lingmao_prod LOGIN PASSWORD :'pw';
SQL
  echo "已建数据库用户 lingmao_prod"
fi

if [ -z "$(psql_super "SELECT 1 FROM pg_database WHERE datname = 'lingmao_prod'")" ]; then
  psql_super "CREATE DATABASE lingmao_prod OWNER lingmao_prod"
  # 默认所有用户都能连任何库。收回：开发账号 lingmao 连不进生产库
  psql_super "REVOKE CONNECT ON DATABASE lingmao_prod FROM PUBLIC"
  echo "已建数据库 lingmao_prod"
fi
