#!/usr/bin/env bash
# Execute the shipped restore-check script against a disposable Docker/PostgreSQL fixture.
set -euo pipefail
[ "${GITHUB_ACTIONS:-}" = true ] && [ "$(id -u)" = 0 ] || { echo 'Run only as root inside GitHub Actions'; exit 2; }
repo=$(pwd)
lab=$(mktemp -d /var/tmp/scm-restore-ci.XXXXXX)
mkdir -p "$lab/scripts" "$lab/backups/daily"
cp deploy/package/scripts/{lib.sh,check-restore.sh} "$lab/scripts/"
cat > "$lab/compose.yml" <<'YAML'
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: scm
      POSTGRES_DB: scm
      POSTGRES_HOST_AUTH_METHOD: trust
    tmpfs: /var/lib/postgresql/data
    healthcheck:
      test: [CMD-SHELL, pg_isready -U scm]
      interval: 1s
      timeout: 5s
      retries: 60
  backup:
    image: postgres:16
    environment:
      PGHOST: postgres
      PGUSER: scm
YAML
cd "$lab"
cleanup() { docker compose down -v --remove-orphans >/dev/null 2>&1 || true; rm -rf "$lab"; }
trap cleanup EXIT
docker compose up -d --wait postgres
docker compose exec -T postgres psql -U scm -d scm -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE fixture(id int PRIMARY KEY, qty numeric(14,3) CHECK(qty >= 0));
INSERT INTO fixture VALUES(1,1.125),(2,2.250);
SQL
docker compose exec -T postgres pg_dump -U scm -d scm -Fc > backups/daily/scm-ci.dump
snapshot_sha=$(sha256sum backups/daily/scm-ci.dump | cut -d' ' -f1)
docker compose exec -T postgres psql -U scm -d scm -v ON_ERROR_STOP=1 -c 'DELETE FROM fixture WHERE id=2' >/dev/null
bash scripts/check-restore.sh backups/daily/scm-ci.dump > check.log 2>&1
cat check.log
grep -q '当前行数减少（可由合法删除/归档造成）' check.log
grep -q '数据对账未验证' check.log
if grep -q '业务表少了不正常' check.log; then exit 1; fi
# Same-snapshot assertions are separate from the live/current row count diagnostic.
docker compose exec -T postgres createdb -U scm scm_snapshot_test
docker compose exec -T postgres pg_restore -U scm --exit-on-error -d scm_snapshot_test < backups/daily/scm-ci.dump
actual=$(docker compose exec -T postgres psql -U scm -d scm_snapshot_test -At -c 'SELECT count(*)::text || chr(124) || sum(qty)::text FROM fixture')
[ "$actual" = '2|3.375' ]
[ "$snapshot_sha" = "$(sha256sum backups/daily/scm-ci.dump | cut -d' ' -f1)" ]
if docker compose exec -T postgres psql -U scm -d scm_snapshot_test -v ON_ERROR_STOP=1 -c 'INSERT INTO fixture VALUES(3,-1)' > constraint.log 2>&1; then echo 'Restored CHECK missing'; exit 1; fi
printf 'invalid dump' > backups/daily/scm-invalid.dump
if bash scripts/check-restore.sh backups/daily/scm-invalid.dump > corrupt.log 2>&1; then echo 'Corrupt dump accepted'; exit 1; fi
echo 'PASS: real restore, same-snapshot quantity/constraint checks, legitimate live deletion and corrupt dump rejection'
