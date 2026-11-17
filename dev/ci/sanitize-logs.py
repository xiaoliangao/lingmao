"""Keep only known synthetic app logs; remove runtime secrets before CI upload."""
import os
import re
import sys
from pathlib import Path

source, destination = map(Path, sys.argv[1:])
destination.mkdir(parents=True, exist_ok=True)
secrets = [os.environ.get(key, "") for key in (
    "JWT_SECRET", "INIT_ADMIN_PASSWORD", "FILE_SIGN_KEY", "SCM_UI_PASSWORD")]
for name in ("app.log", "console.log", "web.log", "seed.log"):
    path = source / name
    if not path.is_file():
        continue
    value = path.read_text(errors="replace")
    for secret in secrets:
        if secret:
            value = value.replace(secret, "[REDACTED]")
    value = re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b", "[REDACTED JWT]", value)
    (destination / name).write_text(value)
