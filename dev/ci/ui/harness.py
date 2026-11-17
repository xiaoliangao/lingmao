"""Loopback-only helpers for synthetic audit regressions; never persist credentials."""
from __future__ import annotations

import io
import json
import os
import re
import struct
import subprocess
import shutil
import zlib
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen
from xml.etree import ElementTree
from zipfile import ZipFile
from zoneinfo import ZoneInfo

LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}
JWT_PATTERN = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")


def validate_origin(value: str, name: str, required_port: int | None = None) -> str:
    value = value.rstrip("/")
    parsed = urlsplit(value)
    if parsed.scheme != "http" or parsed.hostname not in LOCAL_HOSTS:
        raise RuntimeError(f"{name} must be an explicit local HTTP URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path:
        raise RuntimeError(f"{name} must contain only a local origin")
    if required_port is not None and parsed.port != required_port:
        raise RuntimeError(f"{name} must use isolated service port {required_port}")
    return value


def local_url(name: str, *, required_port: int | None = None) -> str:
    return validate_origin(os.environ.get(name, ""), name, required_port)


def read_fixture(path: str) -> dict:
    value = json.loads(Path(path).read_text())
    if value.get("schemaVersion") != 1 or value.get("synthetic") is not True:
        raise RuntimeError("A schemaVersion=1 synthetic fixture manifest is required")
    if not value.get("database", "").endswith("_test"):
        raise RuntimeError("Fixture metadata must name an isolated *_test database")
    return value


def business_day():
    return datetime.now(ZoneInfo("Asia/Shanghai")).date()


def query_path(path: str, params: dict) -> str:
    effective = {k: (str(v).lower() if isinstance(v, bool) else v)
                 for k, v in params.items() if v is not None and v != ""}
    return path + ("?" + urlencode(effective, doseq=True) if effective else "")


class ApiClient:
    def __init__(self, origin: str):
        self.origin = validate_origin(origin, "API origin", 18080)
        self.token: str | None = None
        self.calls: list[dict] = []

    def raw(self, method: str, path: str, body: object = None,
            *, raw_body: bytes | None = None, content_type: str = "application/json"):
        if not path.startswith("/api/") or path.startswith("//"):
            raise ValueError("Only relative /api/ paths are permitted")
        headers = {"Content-Type": content_type}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        payload = raw_body if raw_body is not None else (
            None if body is None else json.dumps(body, ensure_ascii=False).encode())
        request = Request(self.origin + path, data=payload, headers=headers, method=method)
        try:
            with urlopen(request, timeout=45) as response:
                return response.status, response.headers, response.read()
        except HTTPError as error:
            return error.code, error.headers, error.read()

    def envelope(self, method: str, path: str, body: object = None) -> dict:
        status, _headers, raw = self.raw(method, path, body)
        try:
            result = json.loads(raw)
        except (ValueError, UnicodeError) as error:
            raise AssertionError(f"{method} {urlsplit(path).path}: non-JSON response") from error
        if not isinstance(result, dict) or not isinstance(result.get("code"), int):
            raise AssertionError(f"{method} {urlsplit(path).path}: invalid API envelope")
        if not path.startswith("/api/auth/"):
            self.calls.append({"method": method, "path": urlsplit(path).path,
                               "httpStatus": status, "code": result["code"]})
        return result

    def request(self, method: str, path: str, body: object = None):
        result = self.envelope(method, path, body)
        if result["code"] != 0:
            # Never include login bodies, tokens, headers or arbitrary server data in errors.
            raise AssertionError(f"{method} {urlsplit(path).path}: API code {result['code']}")
        return result.get("data")

    def login(self, username: str | None = None):
        username, password = username or os.environ.get("SCM_UI_USERNAME"), os.environ.get("SCM_UI_PASSWORD")
        if not username or not password:
            raise RuntimeError("Synthetic SCM_UI_USERNAME and SCM_UI_PASSWORD are required")
        token = self.request("POST", "/api/auth/login", {"username": username, "password": password})
        if not isinstance(token, str) or not token:
            raise AssertionError("Login did not return a token")
        self.token = token
        return self.request("GET", "/api/auth/me")

    def rows(self, path: str, params: dict | None = None) -> list[dict]:
        result = self.request("GET", query_path(path, {**(params or {}), "page": 1, "size": 500}))
        if not isinstance(result, dict) or not isinstance(result.get("list"), list):
            raise AssertionError("Expected a paged real-API result")
        if result.get("total", 0) > 500:
            raise AssertionError("Synthetic result unexpectedly exceeds one exhaustive test page")
        return result["list"]


ORDER_FIELDS = (
    "companyId", "orderType", "customerId", "customerOrderNo", "contractNo", "brand",
    "orderDate", "requiredDate", "salesId", "merchId", "currency", "deliveryReq", "remark",
)


def order_update(detail: dict) -> dict:
    body = {key: detail.get(key) for key in ORDER_FIELDS}
    body["version"] = detail["version"]
    body["items"] = [
        {key: item.get(key) for key in ("styleSkuId", "qty", "price", "lineDueDate", "remark")}
        for item in detail["items"]
    ]
    return body


def line_values(detail: dict) -> dict:
    return {item["styleSkuId"]: (item["qty"], item["price"], item.get("lineDueDate"), item.get("remark"))
            for item in detail["items"]}


def cost_values(cost: dict) -> tuple:
    return (cost["costMode"], cost.get("buyPrice"),
            [(item["name"], item["amount"], item.get("remark")) for item in cost["otherItems"]])


def pack_update(detail: dict, cost: dict | None = None) -> dict:
    """Roundtrip editable pack fields without leaking response-only display fields."""
    def fields(row, names):
        return {name: row.get(name) for name in names.split()}
    def images(rows):
        return [fields(row, "url thumbUrl name size") for row in rows]
    result = fields(detail, "versionLock versionNote utilPct packingMode")
    result["specPoints"] = [fields(row, "point method toleranceCm gradeCm remark values") for row in detail["specPoints"]]
    result["bomItems"] = []
    for row in detail["bomItems"]:
        value = fields(row, "part materialId skuMode usageMode usageQty sizeUsages lossPct source supplierId refPrice priceSourceId priceTaxIncluded priceFreightIncluded remark")
        value["skus"] = [fields(sku, "colorId sizeCode materialSkuId refPrice") for sku in row["skus"]]
        result["bomItems"].append(value)
    result["craftSections"] = [{**fields(row, "section content"), "images": images(row["images"])} for row in detail["craftSections"]]
    result["processes"] = [{**fields(row, "category name requirement stdSeconds price remark"), "images": images(row["images"])} for row in detail["processes"]]
    result["cost"] = cost
    return result


def count_update(detail: dict, quantity: float | None = None) -> dict:
    return {"version": detail["version"], "countDate": detail["countDate"],
            "counterId": detail["counterId"], "remark": detail.get("remark"),
            "items": [{"materialSkuId": x["materialSkuId"], "countedQty": quantity,
                       "remark": x.get("remark")} for x in detail["items"]]}


def node_plan(detail: dict, day_offset: int = 0) -> dict:
    from datetime import date, timedelta
    return {"planRevision": detail["planRevision"], "nodes": [
        {"nodeId": x["id"],
         "planStart": (date.fromisoformat(x["planStart"]) + timedelta(days=day_offset)).isoformat(),
         "planFinish": (date.fromisoformat(x["planFinish"]) + timedelta(days=day_offset)).isoformat()}
        for x in detail["nodes"]]}


def assert_decimal_equal(actual, expected):
    if Decimal(str(actual)) != Decimal(str(expected)):
        raise AssertionError("Exact synthetic decimal value differs")


def anonymous_png() -> bytes:
    """A generated 16×16 solid swatch, not an external or production image."""
    def chunk(kind: bytes, value: bytes) -> bytes:
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value) & 0xffffffff)
    rows = b"".join(b"\x00" + bytes((195, 176, 145, 255)) * 16 for _ in range(16))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def spreadsheet_rows(content: bytes) -> list[list[str]]:
    """Read only the anonymous exported XLSX cell values, with stdlib OOXML parsing."""
    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    with ZipFile(io.BytesIO(content)) as archive:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
            shared = ["".join(x.itertext()) for x in root.findall("m:si", ns)]
        result = []
        for sheet in sorted(n for n in archive.namelist() if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", n)):
            root = ElementTree.fromstring(archive.read(sheet))
            for row in root.findall(".//m:sheetData/m:row", ns):
                cells = []
                for cell in row.findall("m:c", ns):
                    text = cell.findtext("m:v", default="", namespaces=ns)
                    if cell.get("t") == "s":
                        text = shared[int(text)]
                    elif cell.get("t") == "inlineStr":
                        inline = cell.find("m:is", ns)
                        text = "".join(inline.itertext()) if inline is not None else ""
                    cells.append(text)
                result.append(cells)
        return result


def pdf_text(content: bytes) -> str:
    if not content.startswith(b"%PDF-"):
        raise AssertionError("The real endpoint did not return a PDF")
    executable = shutil.which("pdftotext")
    if not executable:
        raise RuntimeError("Install poppler-utils in the UI test runner")
    process = subprocess.run([executable, "-layout", "-", "-"], input=content,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if process.returncode:
        raise AssertionError("Anonymous PDF text extraction failed")
    return re.sub(r"\s+", "", process.stdout.decode("utf-8"))


def sanitized_error(message: str, token: str | None = None) -> str:
    for secret in (token, os.environ.get("SCM_UI_PASSWORD")):
        if secret:
            message = message.replace(secret, "[redacted]")
    message = re.sub(r"(?i)Bearer\s+\S+", "Bearer [redacted]", message)
    # Browser login creates a separate token; redact JWT-shaped text even when it
    # is not the ApiClient's token and was never captured by this test harness.
    message = JWT_PATTERN.sub("[redacted]", message)
    message = re.sub(r"https?://[^\s\"']+", lambda m: urlsplit(m.group()).path, message)
    return message[:1000]


def write_evidence(path: Path, value: dict, token: str | None = None):
    """Reject secret-bearing structures rather than silently publishing them."""
    forbidden = {"password", "token", "authorization", "cookie", "secret", "loginbody", "storage_state"}
    def check(item):
        if isinstance(item, dict):
            for key, child in item.items():
                if str(key).lower() in forbidden:
                    raise AssertionError("Evidence contains a forbidden credential field")
                check(child)
        elif isinstance(item, (list, tuple)):
            for child in item:
                check(child)
    check(value)
    rendered = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    for secret in (token, os.environ.get("SCM_UI_PASSWORD")):
        if secret and secret in rendered:
            raise AssertionError("Evidence contains secret material")
    if JWT_PATTERN.search(rendered):
        raise AssertionError("Evidence contains token-shaped material")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(rendered)
