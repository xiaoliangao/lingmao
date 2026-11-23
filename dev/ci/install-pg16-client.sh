#!/usr/bin/env bash
# The ubuntu-latest image can change independently of the PostgreSQL 16 service.
# Official instructions and signing-key fingerprint:
# https://www.postgresql.org/download/linux/ubuntu/
set -euo pipefail

pg16_fail() {
  printf 'PostgreSQL 16 CI client setup: %s\n' "$1" >&2
  return 1
}

pg16_verify_key() {
  local primary_fingerprints
  primary_fingerprints="$(awk -F: '
    $1 == "pub" { primary = 1 }
    $1 == "fpr" && primary { print $10; primary = 0 }
  ' <<< "$1")"
  [[ "$primary_fingerprints" == 'B97B0AFCAA1A47F044F244A07FCC7D46ACCC4CF8' ]] ||
    pg16_fail 'the downloaded PGDG key must contain exactly the official primary key'
}

pg16_verify_clients() {
  local bin_dir="$1" client version
  for client in psql pg_dump pg_restore; do
    version="$("$bin_dir/$client" --version)" || return 1
    [[ "$version" =~ ^${client}\ \(PostgreSQL\)\ 16\.[0-9]+([[:space:]]|$) ]] || {
      pg16_fail "$client must be PostgreSQL 16 at $bin_dir"
      return 1
    }
    printf '%s\n' "$version"
  done
}

pg16_install_main() {
  [[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux ]] ||
    { pg16_fail 'installation is restricted to the disposable Linux GitHub Actions runner'; return 1; }
  # This is a trusted OS-owned file, not a repository or network response.
  . /etc/os-release
  [[ "$ID" == ubuntu && "${VERSION_CODENAME:-}" =~ ^[a-z][a-z0-9]+$ ]] ||
    { pg16_fail 'an Ubuntu release codename is required'; return 1; }
  local candidate architecture metadata
  candidate="$(LC_ALL=C apt-cache policy postgresql-client-16 | awk '$1 == "Candidate:" { print $2 }')"
  if [[ -z "$candidate" || "$candidate" == '(none)' ]]; then
    architecture="$(dpkg --print-architecture)"
    [[ "$architecture" =~ ^(amd64|arm64|ppc64el)$ ]] ||
      { pg16_fail 'unsupported Ubuntu PGDG architecture'; return 1; }
    PG16_CI_TEMP="$(mktemp -d)"
    trap 'rm -rf -- "$PG16_CI_TEMP"' EXIT
    mkdir -m 0700 "$PG16_CI_TEMP/gnupg"
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
      --connect-timeout 10 --max-time 60 --retry 2 \
      --output "$PG16_CI_TEMP/pgdg.asc" https://www.postgresql.org/media/keys/ACCC4CF8.asc
    metadata="$(gpg --no-options --homedir "$PG16_CI_TEMP/gnupg" --batch --with-colons \
      --show-keys "$PG16_CI_TEMP/pgdg.asc")"
    pg16_verify_key "$metadata"
    sudo install -d -m 0755 /usr/share/postgresql-common/pgdg
    sudo install -m 0644 "$PG16_CI_TEMP/pgdg.asc" /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc
    cat > "$PG16_CI_TEMP/pgdg.sources" <<EOF
Types: deb
URIs: https://apt.postgresql.org/pub/repos/apt
Suites: ${VERSION_CODENAME}-pgdg
Architectures: ${architecture}
Components: main
Signed-By: /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc
EOF
    sudo install -m 0644 "$PG16_CI_TEMP/pgdg.sources" /etc/apt/sources.list.d/pgdg.sources
    sudo apt-get update -qq
  fi
  # Do not install the moving postgresql-client meta-package or a local server.
  sudo apt-get install -y --no-install-recommends postgresql-client-16
  pg16_verify_clients /usr/lib/postgresql/16/bin
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  pg16_install_main "$@"
fi
