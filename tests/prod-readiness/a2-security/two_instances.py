#!/usr/bin/env python3
"""T08/T10 with two real JVMs sharing PostgreSQL/Redis and one private storage volume.

Run against a fresh disposable *_test database. Requires psycopg 3, Java 21,
an executable application jar, PostgreSQL log_statement=all and parameter logging
disabled. No credentials or response bodies are printed. See README.md.
"""
import argparse
import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

import psycopg


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def request(port, path, body=None, token=None, cookie=None, method=None, headers=None):
    outgoing = {"Content-Type": "application/json", **(headers or {})}
    if token:
        outgoing["Authorization"] = "Bearer " + token
    if cookie:
        outgoing["Cookie"] = cookie
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=None if body is None else json.dumps(body).encode(), headers=outgoing, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=25)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        payload = json.load(response)
        cookies = response.headers.get_all("Set-Cookie") or []
        return response.status, payload, "; ".join(c.split(";", 1)[0] for c in cookies), cookies


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def expired_access(token, secret):
    """Retain a real issued identity/session, with a valid signature but expired claims."""
    header_part, claims_part, _ = token.split(".")
    decode = lambda part: json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    header, claims = decode(header_part), decode(claims_part)
    digests = {"HS256": hashlib.sha256, "HS384": hashlib.sha384, "HS512": hashlib.sha512}
    check(header.get("alg") in digests, "Unexpected JWT signing algorithm")
    claims.update(iat=int(time.time()) - 86400, exp=int(time.time()) - 60)
    encoded = base64.urlsafe_b64encode(json.dumps(claims, separators=(",", ":")).encode()).rstrip(b"=").decode()
    unsigned = header_part + "." + encoded
    signature = hmac.new(base64.b64decode(secret), unsigned.encode(), digests[header["alg"]]).digest()
    return unsigned + "." + base64.urlsafe_b64encode(signature).rstrip(b"=").decode()


def run(args):
    for key in ("PG_DSN", "DB_URL", "DB_USER", "DB_PASSWORD", "REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD"):
        check(key in os.environ, "Missing required test environment: " + key)
    work = Path(args.work_dir).resolve()
    work.mkdir(parents=True, exist_ok=True)
    check(args.pg_log.is_file(), "PostgreSQL query log is not readable")
    with psycopg.connect(os.environ["PG_DSN"], autocommit=True) as db:
        check(db.execute("SELECT current_database()").fetchone()[0].endswith("_test"), "Database must have the disposable _test suffix")
        check(db.execute("SELECT to_regclass('public.sys_user')").fetchone()[0] is None, "This test requires a fresh empty database")
        check(db.execute("SHOW log_statement").fetchone()[0] == "all", "PostgreSQL must log actual statements for the loader assertion")
        check(db.execute("SHOW log_parameter_max_length").fetchone()[0] == "0", "PostgreSQL bind parameter logging must be disabled")
        check(db.execute("SHOW log_parameter_max_length_on_error").fetchone()[0] == "0", "PostgreSQL error parameter logging must be disabled")
        password = secrets.token_urlsafe(24)
        tenant = "a2_" + secrets.token_hex(12)
        env = {**os.environ, "SPRING_PROFILES_ACTIVE": "prod",
               "JWT_SECRET": base64.b64encode(secrets.token_bytes(48)).decode(),
               "INIT_ADMIN_PASSWORD": password, "FILE_SIGN_KEY": secrets.token_hex(16),
               "UPLOAD_DIR": str(work / "uploads")}
        ports = [free_port(), free_port()]
        check(ports[0] != ports[1], "Could not allocate distinct test ports")
        processes, logs = [], []
        summary = {"status": "FAILED", "profile": "prod", "instanceCount": 0,
                   "requestCount": 0, "actualPgLoads": None, "pgLogBarrier": False, "sessionChecks": {}}
        try:
            for index, port in enumerate(ports):
                log = (work / f"instance-{index}.log").open("w")
                logs.append(log)
                command = [args.java, "-Xmx512m", "-XX:ActiveProcessorCount=4", "-jar", str(args.jar.resolve()), f"--server.port={port}",
                           "--server.address=127.0.0.1", f"--lingmao.tenant-id={tenant}",
                           "--lingmao.jobs.enabled=false", "--lingmao.export.dir=" + str(work / "exports"),
                           "--logging.file.name=" + str(work / f"instance-{index}-application.log"),
                           "--logging.logback.rollingpolicy.file-name-pattern=" + str(work / f"instance-{index}-application-%d{{yyyy-MM-dd}}.%i.log"),
                           "--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"]
                process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
                processes.append(process)
                deadline = time.monotonic() + 90
                while True:
                    check(process.poll() is None, f"Instance {index} exited during startup; inspect its private test log")
                    try:
                        if request(port, "/api/version")[0] == 200:
                            break
                    except (OSError, ValueError):
                        pass
                    check(time.monotonic() < deadline, f"Instance {index} startup timed out")
                    time.sleep(0.2)
                summary["instanceCount"] += 1
            login = request(ports[0], "/api/auth/login", {"username": "admin", "password": password})
            check(login[0] == 200 and login[1]["code"] == 0, "Real login failed")
            check(len(login[3]) == 2 and all("HttpOnly" in c and "SameSite=Strict" in c and "Path=/api/auth" in c for c in login[3]),
                  "Refresh and browser cookies must both be HttpOnly and SameSite Strict")
            summary["sessionChecks"]["httpOnlySameSiteCookies"] = True
            def refresh(port, cookie):
                return request(port, "/api/auth/refresh", {}, cookie=cookie, headers={"X-Requested-With": "XMLHttpRequest"})
            ready = threading.Barrier(2)
            def rotate(port):
                ready.wait(timeout=10)
                return refresh(port, login[2])
            with concurrent.futures.ThreadPoolExecutor(2) as pool:
                pair = list(pool.map(rotate, ports))
            check(all(r[0] == 200 and r[1]["code"] == 0 for r in pair), "Cross-instance concurrent refresh failed")
            check(pair[0][1]["data"] == pair[1][1]["data"] and pair[0][2] == pair[1][2], "Rotation did not replay the same successor")
            summary["sessionChecks"]["concurrentRefreshSameSuccessor"] = True
            check(refresh(ports[1], login[2])[0] == 200, "Second grace replay should succeed")
            check(refresh(ports[0], login[2])[0] == 401, "Grace replay count is not bounded")
            summary["sessionChecks"]["graceReplayLimit"] = True
            time.sleep(3.1)
            check(refresh(ports[1], login[2])[0] == 401, "Expired predecessor still accepted")
            summary["sessionChecks"]["expiredPredecessorRejected"] = True
            current = refresh(ports[0], pair[0][2])
            check(current[0] == 200, "Rejected predecessor revoked its valid successor")
            summary["sessionChecks"]["validSuccessorSurvives"] = True
            token = current[1]["data"]
            # An actual non-empty, unique dictionary prevents accidental warm-hit or absent-type claims.
            dictionary = "a2cold_" + secrets.token_hex(8)
            db.execute("INSERT INTO sys_dict_type(code,name) VALUES(%s,'A2 concurrent options')", (dictionary,))
            db.execute("INSERT INTO sys_dict_data(type_code,label,value,sort) VALUES(%s,'A2 option','a2-value',1)", (dictionary,))
            offset = args.pg_log.stat().st_size
            barrier = threading.Barrier(101)
            def read_options(index):
                barrier.wait(timeout=15)
                return request(ports[index % 2], "/api/dict-data/by-type/" + dictionary, token=token)
            with concurrent.futures.ThreadPoolExecutor(100) as pool:
                # Keep the first actual loader waiting briefly in PostgreSQL while both HTTP
                # servers receive the ready wave. It cannot finish and turn this into warm hits.
                with db.transaction():
                    db.execute("LOCK TABLE sys_dict_data IN ACCESS EXCLUSIVE MODE")
                    pending = [pool.submit(read_options, i) for i in range(100)]
                    barrier.wait(timeout=15)
                    summary["requestCount"] = 100
                    time.sleep(0.2)
                replies = [future.result(timeout=25) for future in pending]
            check(all(r[0] == 200 and r[1]["code"] == 0 and len(r[1]["data"]) == 1 and r[1]["data"][0]["value"] == "a2-value" for r in replies),
                  "Concurrent dictionary response was missing its real option")
            # A CI docker-log follower is asynchronous. Wait for a marker emitted
            # after every HTTP result, so a delayed log cannot undercount loaders.
            log_barrier = "a2_log_barrier_" + secrets.token_hex(16)
            db.execute("SELECT 1 /* " + log_barrier + " */")
            log_deadline = time.monotonic() + 10
            while True:
                with args.pg_log.open("rb") as query_log:
                    query_log.seek(offset)
                    statements = query_log.read().decode("utf-8", errors="replace")
                if log_barrier in statements:
                    break
                check(time.monotonic() < log_deadline, "PostgreSQL log follower did not reach the post-request barrier")
                time.sleep(0.05)
            summary["pgLogBarrier"] = True
            loads = len(re.findall(r"LOG:\s+execute[^\n]*SELECT[^\n]*FROM sys_dict_data", statements, re.I))
            summary["actualPgLoads"] = loads
            check(loads == 1, f"100 ready requests caused {loads} actual PostgreSQL dictionary SELECTs instead of one")
            replacement = secrets.token_urlsafe(24)
            changed = request(ports[1], "/api/auth/password", {"oldPassword": password, "newPassword": replacement}, token=token, method="PUT")
            check(changed[1]["code"] == 0, "Actual password change failed")
            check(refresh(ports[0], current[2])[0] == 401, "Password change did not revoke refresh on the other instance")
            summary["sessionChecks"]["passwordChangeRevokesRefresh"] = True
            check(request(ports[1], "/api/auth/me", token=token)[0] == 401, "Password change left the access token valid")
            summary["sessionChecks"]["passwordChangeRevokesAccess"] = True
            again = request(ports[1], "/api/auth/login", {"username": "admin", "password": replacement})
            check(again[1]["code"] == 0, "Login with the changed password failed")
            expired = expired_access(again[1]["data"], env["JWT_SECRET"])
            check(request(ports[0], "/api/auth/me", token=expired)[0] == 401, "Correctly signed expired access was accepted")
            summary["sessionChecks"]["signedExpiredAccessRejected"] = True
            logout = request(ports[0], "/api/auth/logout", {}, token=expired, cookie=again[2], headers={"X-Requested-With": "XMLHttpRequest"})
            check(logout[1]["code"] == 0, "Cookie logout was blocked by an expired access token")
            summary["sessionChecks"]["expiredAccessCookieLogout"] = True
            check(refresh(ports[1], again[2])[0] == 401, "Logout failed to revoke refresh on the second instance")
            summary["sessionChecks"]["logoutRevokesRefresh"] = True
            summary["status"] = "PASSED"
        finally:
            # Only these enumerated outcomes are a CI artifact; never copy the raw PG log.
            result = json.dumps(summary, sort_keys=True, ensure_ascii=True)
            (work / "summary.json").write_text(result + "\n")
            print(result, flush=True)
            for process in reversed(processes):
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            for log in logs:
                log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--pg-log", type=Path, required=True)
    parser.add_argument("--work-dir", default=tempfile.mkdtemp(prefix="a2-instances-"))
    run(parser.parse_args())
