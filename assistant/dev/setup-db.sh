#!/bin/sh
# 在本机开发库（dev/compose.yml 的 scm-dev-postgres）里给助手建库账号和 schema，重跑不报错。
#   用法：assistant/dev/setup-db.sh [库名]     默认 scm（开发库）；DB 测试用 scm_assistant_test，没有就建
# 助手的账号 scm_assistant 只拥有 assistant 这个 schema：app 的表（public 下）没给它授权，读不到也改不了（RFC 017 §3.4）。
# 本机库是 trust 认证，所以不设密码；部署包里的账号和密码由安装脚本建（见部署手册的智能助手一节）
set -eu
DB=${1:-scm}
case "$DB" in *[!a-z0-9_]*|'') echo "库名只能是小写字母、数字、下划线：${DB}" >&2; exit 1 ;; esac
PSQL="docker exec -i scm-dev-postgres psql -v ON_ERROR_STOP=1 -q -U scm"

if [ "$($PSQL -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB'")" != "1" ]; then
  $PSQL -d postgres -c "CREATE DATABASE $DB"
fi
$PSQL -d "$DB" <<'SQL'
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scm_assistant') THEN
    CREATE ROLE scm_assistant LOGIN;
  END IF;
END $$;
CREATE SCHEMA IF NOT EXISTS assistant AUTHORIZATION scm_assistant;
ALTER ROLE scm_assistant SET search_path = assistant;
SQL
echo "好了：库 ${DB}，账号 scm_assistant，schema assistant"
echo "连接串：ASSISTANT_DATABASE_URL=postgres://scm_assistant@127.0.0.1:5433/${DB}"
