"""Redact transient auth values in text test reports before artifact upload."""
from pathlib import Path
import re

roots = [Path('target/surefire-reports'), Path('target/failsafe-reports'), Path('test-artifacts')]
jwt = re.compile(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b')
header = re.compile(r'(?im)^([^\r\n]*(?:Authorization|Set-Cookie|Cookie)\s*[:=]\s*)[^\r\n]*')
credential = re.compile(r'''(?i)(["']?(?:password|passwd|jwt[_-]?secret|file[_-]?sign[_-]?key|init[_-]?admin[_-]?password|authorization|set-cookie|cookie)["']?\s*[:=]\s*)("[^"\r\n]*"|'[^'\r\n]*'|[^\s,;<]+)''')
for root in roots:
    if not root.exists():
        continue
    for path in root.rglob('*'):
        if not path.is_file() or path.suffix.lower() not in {'.txt', '.xml', '.json', '.log'}:
            continue
        value = path.read_text(errors='replace')
        value = jwt.sub('[REDACTED JWT]', value)
        value = header.sub(r'\1[REDACTED]', value)
        value = credential.sub(r'\1"[REDACTED]"', value)
        path.write_text(value)
print('Sanitized text test reports; screenshots and synthetic exports are unchanged.')
