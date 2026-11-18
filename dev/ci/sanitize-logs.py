"""Keep only known synthetic app logs; remove runtime secrets before CI upload."""
import sys
from pathlib import Path

from redaction import redact_text

source, destination = map(Path, sys.argv[1:])
destination.mkdir(parents=True, exist_ok=True)
for name in ("app.log", "console.log", "web.log", "seed.log"):
    path = source / name
    if not path.is_file():
        continue
    (destination / name).write_text(redact_text(path.read_text(errors="replace")))
