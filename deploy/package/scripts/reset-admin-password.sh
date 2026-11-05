#!/usr/bin/env bash
# 忘了管理员密码：sudo /opt/scm/scripts/reset-admin-password.sh
#
# 给 admin 生成一个新的随机密码，同时启用 admin、补上超级管理员角色、清掉登录失败计数（输错 5 次被锁的也能马上登录）。
# 新密码只在屏幕上显示这一次，哪里都不存。
#
# 密码的哈希用 PostgreSQL 自带的 pgcrypto 算：crypt(密码, gen_salt('bf', 10)) 就是 BCrypt，
# 和应用里的 BCryptPasswordEncoder 是同一种格式（$2a$10$...），应用直接认，不需要另起一个应用来算（RFC 002 §9.6）。
set -euo pipefail

TARGET=$(cd "$(dirname "$0")/.." && pwd)
. "$TARGET"/scripts/lib.sh

require_root
cd "$TARGET"
if [ -z "$(docker compose ps -q --status running postgres 2>/dev/null || true)" ]; then
  die "数据库没在运行。先在 $TARGET 里执行 sudo docker compose up -d"
fi

password=$(random_password 16)

# 密码从标准输入传给 psql，不放在命令行参数里（命令行参数别的用户用 ps 能看到）。
# 这个会话不记慢查询日志：数据库开着"超过 1 秒的语句写日志"，语句里有明文密码
# pgcrypto 装在维护库 postgres 里，不装进业务库 scm：业务库的备份、恢复不多出一个扩展
hash=$(docker compose exec -T postgres psql -U scm -d postgres -At -q -v ON_ERROR_STOP=1 <<SQL | grep -E '^\$2[aby]\$' || true
SET client_min_messages = warning;
SET log_min_duration_statement = -1;
SET log_statement = 'none';
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SELECT crypt('$password', gen_salt('bf', 10));
SQL
)
if [ -z "$hash" ]; then
  die "算密码哈希失败，上面是数据库的报错"
fi

result=$(docker compose exec -T postgres psql -U scm -d scm -At -q -v ON_ERROR_STOP=1 <<SQL
BEGIN;
UPDATE sys_user SET password = '$hash', status = 1, update_time = now()
WHERE username = 'admin' AND deleted = false;
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username = 'admin' AND u.deleted = false AND r.code = 'admin' AND r.deleted = false
ON CONFLICT DO NOTHING;
SELECT 'admin_count=' || count(*) FROM sys_user WHERE username = 'admin' AND deleted = false;
COMMIT;
SQL
)
case $result in
  *admin_count=1*) ;;
  *admin_count=0*)
    die "库里没有 admin 这个用户。重启一下应用，它会用 .env 里的 INIT_ADMIN_PASSWORD 重新建 admin：sudo docker compose restart app" ;;
  *) die "改密码失败，上面是数据库的报错" ;;
esac

# 登录失败计数（RFC 001 §7，Redis 里的 login:fail:admin）。Redis 的密码在 redis 容器的 REDISCLI_AUTH 里，redis-cli 自己会用
if ! docker compose exec -T redis redis-cli DEL login:fail:admin > /dev/null 2>&1 < /dev/null; then
  warn "没清掉登录失败计数（Redis 没在运行？）。admin 要是被锁了，等 15 分钟再登录"
fi

if [ -t 0 ] && [ -t 1 ]; then
  printf '\n密码改好了。新密码只显示这一次：远程协助的话先停止屏幕共享，然后按回车显示。'
  read -r _ || true
fi
cat <<EOF

  账号    admin
  新密码  $password

登录以后马上在右上角的用户菜单里改掉。记下来以后可以执行 clear 清屏。
EOF
