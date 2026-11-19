#!/usr/bin/env bash
# Only ephemeral GitHub Actions services. Never point this harness at a live SCM.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true ]] || { echo 'Run this entry point only on an isolated CI runner.' >&2; exit 2; }
phase=${1:?before or after}
[[ $phase == before || $phase == after ]] || exit 2
repo=$(cd "$(dirname "$0")/../.." && pwd)
: "${SCM_CI_POSTGRES_CONTAINER:?PostgreSQL 16 service container is required}"
jar=${SCM_CI_JAR:-$repo/target/scm-0.0.1-SNAPSHOT.jar}
web=${SCM_CI_WEB:-$repo/web/dist}
[[ -f $jar && -f $web/index.html ]] || { echo 'Build artifacts are missing.' >&2; exit 2; }
work=$(mktemp -d "${RUNNER_TEMP:-/tmp}/scm-ui-$phase.XXXXXX")
artifacts="$repo/test-artifacts/$phase"
mkdir -p "$artifacts"
app_pid=''; web_pid=''
cleanup() {
  result=$?
  trap - EXIT
  [[ -z $app_pid ]] || kill "$app_pid" 2>/dev/null || true
  [[ -z $web_pid ]] || kill "$web_pid" 2>/dev/null || true
  wait || true
  python "$repo/dev/ci/sanitize-logs.py" "$work" "$artifacts/logs"
  rm -rf "$work"
  exit "$result"
}
trap cleanup EXIT
database=scm_smoke_test
redis_db=13
if [[ $phase == before ]]; then database=scm_before_test; redis_db=12; fi
docker exec "$SCM_CI_POSTGRES_CONTAINER" createdb -U scm "$database"
export DB_URL="jdbc:postgresql://127.0.0.1:5433/$database" DB_USER=scm DB_PASSWORD="${SCM_CI_DB_PASSWORD:?Runtime credentials missing}"
export REDIS_HOST=127.0.0.1 REDIS_PORT=6380 REDIS_PASSWORD="${SCM_CI_REDIS_PASSWORD:?Runtime credentials missing}" REDIS_DB="$redis_db"
export JWT_SECRET="$(python -c 'import secrets; print(secrets.token_hex(32))')"
export INIT_ADMIN_PASSWORD="$(python -c 'import secrets; print("Test-" + secrets.token_urlsafe(18))')"
export FILE_SIGN_KEY="$(python -c 'import secrets; print(secrets.token_hex(16))')"
printf '::add-mask::%s\n' "$JWT_SECRET" "$INIT_ADMIN_PASSWORD" "$FILE_SIGN_KEY"
export UPLOAD_DIR="$work/uploads" EXPORT_DIR="$work/exports" SPRING_PROFILES_ACTIVE=prod
export LOGGING_FILE_NAME="$work/app.log"
java -Xmx512m -XX:ActiveProcessorCount=4 -jar "$jar" --server.port=18080 "--logging.logback.rollingpolicy.file-name-pattern=$work/app-%d{yyyy-MM-dd}.%i.log" > "$work/console.log" 2>&1 &
app_pid=$!
for attempt in {1..120}; do
  if curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:18080/api/version > "$artifacts/health.json"; then break; fi
  kill -0 "$app_pid" 2>/dev/null || { echo 'Application exited before health check.' >&2; exit 1; }
  sleep 1
done
python - "$artifacts/health.json" <<'PY'
import json,sys
value=json.load(open(sys.argv[1]))
assert isinstance(value,dict) and value.get('code') == 0, 'Health envelope failed'
PY
docker exec -i "$SCM_CI_POSTGRES_CONTAINER" psql -U scm -d "$database" -v ON_ERROR_STOP=1 < "$repo/dev/seed-demo.sql" > "$work/seed.log" 2>&1
docker exec "$SCM_CI_POSTGRES_CONTAINER" psql -U scm -d "$database" -v ON_ERROR_STOP=1 -c 'UPDATE sys_user SET default_company_id=1 WHERE id=1' >> "$work/seed.log" 2>&1
export SCM_UI_API_BASE=http://127.0.0.1:18080 SCM_UI_BASE_URL=http://127.0.0.1:18081
export SCM_UI_USERNAME=admin SCM_UI_PASSWORD="$INIT_ADMIN_PASSWORD"
export SCM_UI_FIXTURE_INPUT="$work/input.json" SCM_UI_FIXTURE="$work/fixture.json" SCM_UI_SCREENSHOTS="$artifacts"
python - "$repo/dev/ci/ui/fixture-input.json" "$SCM_UI_FIXTURE_INPUT" "$database" <<'PY'
import json,sys
value=json.load(open(sys.argv[1])); value['database']=sys.argv[3]
with open(sys.argv[2],'w') as out: json.dump(value,out)
PY
python "$repo/dev/ci/ui/serve_web.py" "$web" > "$work/web.log" 2>&1 &
web_pid=$!
if [[ $phase == before ]]; then
  python "$repo/dev/ci/ui/prepare_before.py"
  python "$repo/dev/ci/ui/capture_baseline.py"
else
  python "$repo/dev/ci/ui/prepare_fixtures.py"
  cp "$work/fixture-evidence.json" "$artifacts/fixture-evidence.json"
  (cd "$repo/dev/ci/ui" && python -m unittest -v test_audit_ui)
  python "$repo/tests/prod-readiness/a3-browser/real-session.py"
fi
