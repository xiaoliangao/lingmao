"""Run the existing prototype checks with the same managed browser as UI CI."""
import os
import socket
import subprocess
from pathlib import Path

from playwright.sync_api import sync_playwright


repo = Path(__file__).resolve().parents[2]
with socket.socket() as probe:
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, args=[f"--remote-debugging-port={port}"])
    try:
        browser.new_page(viewport={"width": 1440, "height": 900})
        result = subprocess.run(
            ["node", "docs/原型/check.mjs", "--shots", "test-artifacts/prototype", "--only", "/basics?tab=sizes"],
            cwd=repo, env={**os.environ, "PROTO_CDP_URL": f"http://127.0.0.1:{port}"}, check=False,
        )
    finally:
        browser.close()
raise SystemExit(result.returncode)
