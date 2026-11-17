#!/usr/bin/env bash
# CI-only isolated sshd/chroot; never changes the host sshd or any application directory.
set -euo pipefail
[ "${GITHUB_ACTIONS:-}" = true ] && [ "$(id -u)" = 0 ] || { echo 'Run only as root inside GitHub Actions'; exit 2; }
# Every chroot ancestor must be root-owned and non-writable by others; /tmp is not suitable.
lab=$(mktemp -d /run/scm-readonly-ci.XXXXXX)
scm_account="scm-audit-$$"
scm_sshd_pid=''
cleanup() { kill $scm_sshd_pid 2>/dev/null || true; userdel "$scm_account" 2>/dev/null || true; rm -rf "$lab"; }
trap cleanup EXIT
useradd --system -M -d / -s /bin/sh "$scm_account"
printf '%s:%s\n' "$scm_account" "$(openssl rand -hex 32)" | chpasswd
chmod 755 "$lab"
mkdir -p "$lab/jail/backups" "$lab/jail/uploads" /run/sshd
chmod 755 "$lab/jail" "$lab/jail/backups" "$lab/jail/uploads"
printf 'synthetic-backup\n' > "$lab/jail/backups/sample.dump"
printf 'synthetic-upload\n' > "$lab/jail/uploads/sample.txt"
printf 'outside-boundary\n' > "$lab/outside.txt"
chmod 644 "$lab/jail/backups/sample.dump" "$lab/jail/uploads/sample.txt" "$lab/outside.txt"
ln -s "$lab/outside.txt" "$lab/jail/uploads/escape"
ssh-keygen -q -t ed25519 -N '' -f "$lab/host"
ssh-keygen -q -t ed25519 -N '' -f "$lab/client"
printf 'from="127.0.0.1",restrict %s\n' "$(cat "$lab/client.pub")" > "$lab/authorized_keys"
chmod 644 "$lab/authorized_keys"
cat > "$lab/sshd_config" <<EOF
ListenAddress 127.0.0.1
Port 22222
HostKey $lab/host
PidFile $lab/sshd.pid
AuthorizedKeysFile $lab/authorized_keys
AllowUsers $scm_account
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes yes
Subsystem sftp internal-sftp
ChrootDirectory $lab/jail
ForceCommand internal-sftp -R -d /
DisableForwarding yes
PermitTTY no
X11Forwarding no
LogLevel VERBOSE
EOF
/usr/sbin/sshd -t -f "$lab/sshd_config"
/usr/sbin/sshd -D -e -f "$lab/sshd_config" > "$lab/server.log" 2>&1 &
scm_sshd_pid=$!
# Pin the just-generated local fixture host key, without trusting a discovered network key.
printf '[127.0.0.1]:22222 %s\n' "$(cut -d' ' -f1,2 "$lab/host.pub")" > "$lab/known_hosts"
opts=(-i "$lab/client" -oBatchMode=yes -oStrictHostKeyChecking=yes -oUserKnownHostsFile="$lab/known_hosts" -oConnectTimeout=5)
for n in {1..40}; do if bash -c 'exec 3<>/dev/tcp/127.0.0.1/22222' 2>/dev/null; then break; fi; sleep .1; done
printf 'get /backups/sample.dump %s/read-backup\nget /uploads/sample.txt %s/read-upload\n' "$lab" "$lab" > "$lab/read.batch"
sftp -q -P 22222 "${opts[@]}" -b "$lab/read.batch" "$scm_account@127.0.0.1"
cmp "$lab/read-backup" "$lab/jail/backups/sample.dump"
cmp "$lab/read-upload" "$lab/jail/uploads/sample.txt"
for command in "put $lab/outside.txt /uploads/new.txt" 'rename /uploads/sample.txt /uploads/renamed.txt' 'rm /uploads/sample.txt' "get /uploads/escape $lab/escaped" "get /../../outside.txt $lab/traversed"; do
 printf '%s\n' "$command" > "$lab/deny.batch"
 if sftp -q -P 22222 "${opts[@]}" -b "$lab/deny.batch" "$scm_account@127.0.0.1" > "$lab/deny.log" 2>&1; then echo "Unexpected SFTP operation allowed"; exit 1; fi
done
timeout 5 ssh -p 22222 "${opts[@]}" "$scm_account@127.0.0.1" 'printf COMMAND_EXECUTED' < /dev/null > "$lab/command.log" 2>&1 || true
if grep -q COMMAND_EXECUTED "$lab/command.log"; then echo 'Shell command executed'; exit 1; fi
timeout 5 ssh -tt -p 22222 "${opts[@]}" "$scm_account@127.0.0.1" < /dev/null > "$lab/tty.log" 2>&1 || true
grep -qi 'PTY allocation request failed' "$lab/tty.log"
if timeout 5 ssh -N -p 22222 "${opts[@]}" -oExitOnForwardFailure=yes -R 0:127.0.0.1:9 "$scm_account@127.0.0.1" > "$lab/forward.log" 2>&1; then echo 'Port forwarding allowed'; exit 1; fi
grep -qi 'forwarding failed' "$lab/forward.log"
test ! -e "$lab/jail/uploads/new.txt"
test -f "$lab/jail/uploads/sample.txt"
echo 'PASS: allowed backup/upload reads; rejected writes, rename, delete, shell, PTY, forwarding, traversal and symlink escape'
