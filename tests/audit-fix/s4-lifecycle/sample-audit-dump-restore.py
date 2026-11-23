#!/usr/bin/env python3
"""Restore the actual integration-test database and compare selected synthetic sample roots.

Credentials enter through the child environment, never argv, logs or evidence. The custom
archive contains the whole test database and is private, ephemeral and always removed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time
from urllib.parse import unquote, urlsplit
import uuid


class GateFailure(RuntimeError):
    def __init__(self, message: str, diagnostic: dict[str, object] | None = None):
        super().__init__(message)
        self.diagnostic = diagnostic


def safe_diagnostic(result: subprocess.CompletedProcess[bytes]) -> dict[str, object]:
    """Keep fixed error categories, never PostgreSQL's SQL, values, URLs or raw message."""
    raw = result.stderr or b""
    diagnostic: dict[str, object] = {"exitCode": result.returncode, "stderrBytes": len(raw),
                                    "stderrSha256": hashlib.sha256(raw).hexdigest(), "category": "unclassified"}
    # Only inspect the first ERROR line. CONTEXT/DETAIL and the restore command can contain row values.
    error = re.search(rb"(?m)^(?:pg_restore: error: [^\r\n]*?)?ERROR:\s+([^\r\n]+)", raw)
    if error is None:
        return diagnostic
    message = error.group(1)
    state = re.match(rb"([0-9A-Z]{5})(?::|\s|$)", message)
    if state is not None:
        diagnostic["sqlState"] = state.group(1).decode("ascii")
    if re.search(rb"\bfunction [^\r\n]+ does not exist", message):
        diagnostic["category"] = "undefined_function"
        # These are source-owned identifiers, not arbitrary text copied from a database error.
        for name in ("scm_security_audit_id_valid", "scm_security_audit_link_valid", "scm_sample_audit_identity_valid"):
            if re.search(rb"\bfunction (?:public\.)?" + name.encode("ascii") + rb"\(", message):
                diagnostic["knownObject"] = name
                break
    elif re.search(rb"\brelation [^\r\n]+ does not exist", message):
        diagnostic["category"] = "undefined_relation"
    elif b"violates check constraint" in message:
        diagnostic["category"] = "check_constraint"
    elif b"violates foreign key constraint" in message:
        diagnostic["category"] = "foreign_key_constraint"
    elif b"duplicate key value violates unique constraint" in message:
        diagnostic["category"] = "unique_constraint"
    elif b"permission denied" in message:
        diagnostic["category"] = "permission_denied"
    elif b"statement timeout" in message:
        diagnostic["category"] = "statement_timeout"
    elif b"lock timeout" in message:
        diagnostic["category"] = "lock_timeout"
    return diagnostic


def run(command: list[str], environment: dict[str, str], label: str, *, sql: str | None = None,
        failure_expected: bool = False, timeout: float = 60) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(command, env=environment, input=None if sql is None else sql.encode(),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise GateFailure(label + ": " + type(error).__name__) from None
    if result.returncode != 0 and not failure_expected:
        # PostgreSQL may put SQL/data in stderr; its raw diagnostic is never copied to stdout/artifacts.
        raise GateFailure(label + ": exit " + str(result.returncode), safe_diagnostic(result))
    return result


def clients() -> dict[str, str]:
    directory = os.environ.get("SCM_TEST_PG_BIN")
    found: dict[str, str] = {}
    for name in ("psql", "pg_dump", "pg_restore", "createdb", "dropdb"):
        binary = str(Path(directory) / name) if directory else shutil.which(name)
        if binary is None or not Path(binary).is_file():
            raise GateFailure("Required PostgreSQL client is unavailable: " + name)
        found[name] = binary
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample-id", action="append", required=True, type=int)
    parser.add_argument("--security-role-id", required=True, type=int)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    roots = sorted(set(arguments.sample_id))
    if not 1 <= len(roots) <= 10 or any(value <= 0 or value > 9_223_372_036_854_775_807 for value in roots):
        raise GateFailure("Supply one to ten positive 64-bit sample identities")
    role = arguments.security_role_id
    if role <= 0 or role > 9_223_372_036_854_775_807:
        raise GateFailure("Supply a positive 64-bit security role identity")
    url = os.environ.get("SCM_SAMPLE_TEST_JDBC_URL", "")
    if not url.startswith("jdbc:postgresql://"):
        raise GateFailure("A real integration-test JDBC URL is required")
    try:
        parsed = urlsplit(url.removeprefix("jdbc:"))
        port = parsed.port or 5432
    except ValueError:
        raise GateFailure("The test database URL is invalid") from None
    database = unquote(parsed.path.removeprefix("/"))
    if parsed.hostname not in ("127.0.0.1", "localhost", "::1") or not re.fullmatch(r"[A-Za-z0-9_]+_test", database):
        raise GateFailure("Only a loopback source database ending in _test is permitted")
    if parsed.username is not None or parsed.password is not None:
        raise GateFailure("Credentials must be supplied only in the child environment")
    user = os.environ.get("SCM_SAMPLE_TEST_DB_USER", "")
    if not user:
        raise GateFailure("A test database user is required")
    environment = {key: value for key, value in os.environ.items() if not key.startswith("PG")}
    environment.update(PGHOST=parsed.hostname, PGPORT=str(port), PGUSER=user,
                       PGPASSWORD=os.environ.get("SCM_SAMPLE_TEST_DB_PASSWORD", ""), PGTZ="UTC",
                       PGAPPNAME="s4_sample_restore", PGCONNECT_TIMEOUT="5")
    binary = clients()
    target = "s4_sample_restore_" + uuid.uuid4().hex + "_test"
    private = Path(tempfile.mkdtemp(prefix="s4-sample-restore-"))
    private.chmod(0o700)
    archive = private / "database.dump"
    archive.touch(mode=0o600)
    archive.chmod(0o600)
    evidence: dict[str, object] = {"sourceDatabase": database, "targetDatabase": target,
                                  "sampleIds": [str(value) for value in roots], "securityRoleId": str(role), "passed": False}
    created = False
    failed: str | None = None

    def query(name: str, sql: str, *, expected_failure: bool = False, timeout: float = 15) -> subprocess.CompletedProcess[bytes]:
        return run([binary["psql"], "-X", "-w", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
                    "-v", "VERBOSITY=sqlstate", "-d", name], environment, "psql test probe",
                   sql=sql, failure_expected=expected_failure, timeout=timeout)

    def interrupted(_number: int, _frame: object) -> None:
        raise GateFailure("Restore verification interrupted")

    ids = ",".join(str(value) for value in roots)
    snapshot_sql = f"""
        WITH chosen AS (SELECT id FROM dev_sample WHERE id IN ({ids})),
             orders AS (SELECT id FROM dev_sample_order WHERE sample_id IN (SELECT id FROM chosen)),
             reviews AS (SELECT id FROM dev_sample_review WHERE sample_order_id IN (SELECT id FROM orders))
        SELECT jsonb_build_object(
          'dev_sample', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM dev_sample t WHERE id IN (SELECT id FROM chosen)),
          'dev_sample_color', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY sample_id,color_id),'[]') FROM dev_sample_color t WHERE sample_id IN (SELECT id FROM chosen)),
          'dev_sample_order', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM dev_sample_order t WHERE id IN (SELECT id FROM orders)),
          'dev_sample_order_color', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY sample_order_id,color_id),'[]') FROM dev_sample_order_color t WHERE sample_order_id IN (SELECT id FROM orders)),
          'dev_sample_order_size', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY sample_order_id,seq),'[]') FROM dev_sample_order_size t WHERE sample_order_id IN (SELECT id FROM orders)),
          'dev_sample_node', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM dev_sample_node t WHERE sample_order_id IN (SELECT id FROM orders)),
          'dev_sample_review', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM dev_sample_review t WHERE id IN (SELECT id FROM reviews)),
          'dev_sample_reviewer', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY review_id,user_id),'[]') FROM dev_sample_reviewer t WHERE review_id IN (SELECT id FROM reviews)),
          'sys_data_log', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM sys_data_log t WHERE root_table='dev_sample' AND root_id IN (SELECT id FROM chosen)),
          'sys_user_role', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY user_id),'[]') FROM sys_user_role t WHERE role_id={role}),
          'sys_role_menu', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY menu_id),'[]') FROM sys_role_menu t WHERE role_id={role}),
          'security_data_log', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]') FROM sys_data_log t
            WHERE table_name IN ('sys_user_role','sys_role_menu') AND row_key->>'role_id'='{role}'));
        """
    function_configuration_sql = """
        SELECT jsonb_object_agg(proname,to_jsonb(proconfig)) FROM pg_catalog.pg_proc
        WHERE oid IN ('public.scm_security_audit_link_valid(text,jsonb,text,bigint)'::regprocedure,
                      'public.scm_sample_audit_identity_valid(text,text,bigint,jsonb,text,bigint)'::regprocedure);
        """
    try:
        for number in (signal.SIGTERM, signal.SIGINT):
            signal.signal(number, interrupted)
        server_major = int(query(database, "SHOW server_version_num;").stdout.strip()) // 10_000
        for name in ("pg_dump", "pg_restore"):
            version = run([binary[name], "--version"], environment, "client version").stdout.decode()
            match = re.search(r"\(PostgreSQL\)\s+(\d+)\.", version)
            if match is None or int(match.group(1)) != server_major:
                raise GateFailure("PostgreSQL dump/restore clients must match the actual server major version")
        before = query(database, snapshot_sql).stdout.strip()
        content = json.loads(before)
        if len(content["dev_sample"]) != len(roots) or any(not rows for rows in content.values()):
            raise GateFailure("The actual fixture must contain every sample table and persisted audit events")
        evidence.update(serverMajor=server_major, rowCounts={key: len(rows) for key, rows in content.items()},
                        sourceSnapshotSha256=hashlib.sha256(before).hexdigest(),
                        sourceFunctionConfiguration=json.loads(query(database, function_configuration_sql).stdout))
        run([binary["pg_dump"], "-w", "--format=custom", "--no-owner", "--no-acl", "--lock-wait-timeout=3s",
             "--file=" + str(archive), database], environment, "real pg_dump")
        with archive.open("rb") as content_file:
            archive_sha = hashlib.file_digest(content_file, "sha256").hexdigest()
        evidence.update(archiveSha256=archive_sha, archiveBytes=archive.stat().st_size,
                        privateDirectoryMode=oct(private.stat().st_mode & 0o777), privateArchiveMode=oct(archive.stat().st_mode & 0o777))
        created = True  # A timeout after CREATE must still cause cleanup of this random test-only database.
        run([binary["createdb"], "-w", "--template=template0", "--maintenance-db=" + database, target], environment, "create isolated restore database")
        run([binary["pg_restore"], "-w", "--exit-on-error", "--no-owner", "--no-acl", "--dbname=" + target, str(archive)], environment, "real pg_restore")
        after = query(target, snapshot_sql).stdout.strip()
        evidence["restoredSnapshotSha256"] = hashlib.sha256(after).hexdigest()
        if before != after:
            raise GateFailure("Restored sample business rows and audit facts differ from the actual source snapshot")
        function_configuration = json.loads(query(target, function_configuration_sql).stdout)
        expected_configuration = {name: ["search_path=pg_catalog, public"] for name in
                                  ("scm_security_audit_link_valid", "scm_sample_audit_identity_valid")}
        if function_configuration != expected_configuration:
            raise GateFailure("Restored audit wrappers did not preserve their fixed function search paths")
        # This separate post-restore probe cannot affect pg_restore's own connection or defaults.
        empty_path = json.loads(query(target, f"""
            BEGIN;
            DO $$ BEGIN PERFORM pg_catalog.set_config('search_path','',true); END $$;
            SELECT jsonb_build_object(
              'searchPath',pg_catalog.current_setting('search_path'),
              'sampleRows',(SELECT count(*) FROM public.sys_data_log WHERE root_table='dev_sample' AND root_id IN ({ids})),
              'sampleValid',(SELECT bool_and(public.scm_sample_audit_identity_valid(tenant_key,table_name,row_id,row_key,root_table,root_id))
                FROM public.sys_data_log WHERE root_table='dev_sample' AND root_id IN ({ids})),
              'securityRows',(SELECT count(*) FROM public.sys_data_log WHERE table_name IN ('sys_user_role','sys_role_menu') AND row_key->>'role_id'='{role}'),
              'securityValid',(SELECT bool_and(public.scm_security_audit_link_valid(table_name,row_key,root_table,root_id))
                FROM public.sys_data_log WHERE table_name IN ('sys_user_role','sys_role_menu') AND row_key->>'role_id'='{role}'));
            ROLLBACK;
            """).stdout)
        if empty_path != {"searchPath": "", "sampleRows": len(content["sys_data_log"]), "sampleValid": True,
                          "securityRows": 2, "securityValid": True}:
            raise GateFailure("Audit wrapper verification under an empty caller search path failed")
        evidence.update(restoredFunctionConfiguration=function_configuration, emptySearchPathProbe=empty_path)
        guard_state = json.loads(query(target, """
            SELECT jsonb_build_object(
              'identityValidated',(SELECT convalidated FROM pg_constraint WHERE conrelid='sys_data_log'::regclass AND conname='ck_data_log_row_identity'),
              'sourceTriggers',(SELECT count(*) FROM pg_trigger WHERE tgname='sample_field_audit' AND NOT tgisinternal),
              'identityGuard',(SELECT count(*) FROM pg_trigger WHERE tgrelid='sys_data_log'::regclass AND tgname='sample_audit_identity_guard' AND NOT tgisinternal));
            """).stdout)
        if guard_state != {"identityValidated": True, "sourceTriggers": 8, "identityGuard": 1}:
            raise GateFailure("Restored sample audit constraints or triggers are incomplete")
        rejection = query(target, f"""
            INSERT INTO sys_data_log(tenant_key,table_name,row_id,row_key,root_table,root_id,price_domain,action,diff,transaction_id)
            SELECT c.tenant_key,'dev_sample_order_color',NULL,
              jsonb_build_object('tenant_key',c.tenant_key,'company_id',c.company_id::text,'sample_order_id',c.sample_order_id::text,'color_id',c.color_id::text),
              'dev_sample',CASE WHEN o.sample_id=9223372036854775807 THEN o.sample_id-1 ELSE o.sample_id+1 END,
              'COST','INSERT','{{}}',txid_current()
            FROM dev_sample_order_color c JOIN dev_sample_order o ON o.id=c.sample_order_id AND o.tenant_key=c.tenant_key
            WHERE o.sample_id IN ({ids}) LIMIT 1;
            """, expected_failure=True)
        state = re.search(rb"ERROR:\s+([A-Z0-9]{5})", rejection.stderr)
        if rejection.returncode == 0 or state is None or state.group(1) != b"23514":
            raise GateFailure("Restored real-parent identity guard did not reject a forged sample root")
        append_only = query(target, "UPDATE sys_data_log SET diff=diff WHERE false;", expected_failure=True)
        if append_only.returncode == 0:
            raise GateFailure("Restored audit append-only guard did not reject UPDATE")
        evidence.update(restoredGuards=guard_state, forgedRootSqlState=state.group(1).decode(),
                        appendOnlyRejected=True, exactSnapshotEqual=True, passed=True)
    except (GateFailure, ValueError, KeyError, OSError) as error:
        failed = str(error) if isinstance(error, GateFailure) else type(error).__name__
        evidence.update(passed=False, failure=failed)
        if isinstance(error, GateFailure) and error.diagnostic is not None:
            evidence["failureDiagnostic"] = error.diagnostic
    finally:
        # Do not let a second cancellation interrupt bounded cleanup and leave a private archive.
        for number in (signal.SIGTERM, signal.SIGINT):
            signal.signal(number, signal.SIG_IGN)
        cleanup_deadline = time.monotonic() + 7
        if created:
            try:
                run([binary["dropdb"], "-w", "--if-exists", "--force", "--maintenance-db=" + database, target],
                    environment, "remove isolated restore database", timeout=max(.1, cleanup_deadline - time.monotonic()))
                evidence["targetDatabaseRemoved"] = query(database, "SELECT NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='" + target + "');",
                    timeout=max(.1, cleanup_deadline - time.monotonic())).stdout.strip() == b"t"
                if not evidence["targetDatabaseRemoved"]:
                    raise GateFailure("Restore database cleanup did not finish")
            except GateFailure as error:
                failed = str(error); evidence.update(passed=False, cleanupFailure=failed)
        try:
            shutil.rmtree(private)
            evidence["privateArchiveRemoved"] = not private.exists()
        except OSError:
            failed = "Private restore archive cleanup failed"; evidence.update(passed=False, cleanupFailure=failed)
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"passed": evidence["passed"], "evidence": str(arguments.output), "failure": failed}))
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GateFailure as failure:
        # Messages are fixed labels only; no URL, credential, SQL or subprocess output.
        print(json.dumps({"passed": False, "failure": str(failure)}))
        raise SystemExit(1)
