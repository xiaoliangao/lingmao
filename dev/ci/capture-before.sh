#!/usr/bin/env bash
# Build the PR's actual base commit, then use the same anonymous browser harness.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true ]] || exit 2
base_sha=${1:?PR base SHA required}
[[ $base_sha =~ ^[0-9a-f]{40}$ ]] || exit 2
repo=$(cd "$(dirname "$0")/../.." && pwd)
before=$(mktemp -d "${RUNNER_TEMP:-/tmp}/scm-before.XXXXXX")
cleanup() { git -C "$repo" worktree remove --force "$before" >/dev/null 2>&1 || true; }
trap cleanup EXIT
git -C "$repo" worktree add --detach "$before" "$base_sha"
(
  cd "$before"
  ./mvnw -B -ntp -Dmaven.test.skip=true package
  cd web
  # The approved native build is confined to this temporary baseline checkout.
  if [[ ! -f pnpm-workspace.yaml ]]; then printf 'allowBuilds:\n  esbuild: true\n' > pnpm-workspace.yaml; fi
  pnpm install --frozen-lockfile
  pnpm build
)
SCM_CI_JAR="$before/target/scm-0.0.1-SNAPSHOT.jar" SCM_CI_WEB="$before/web/dist" bash "$repo/dev/ci/run-smoke.sh" before
