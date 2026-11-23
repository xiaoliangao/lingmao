"""Redact transient auth values in text test reports before artifact upload."""
from pathlib import Path

from redaction import redact_text

roots = [Path('target/surefire-reports'), Path('target/failsafe-reports'), Path('test-artifacts'),
         Path('target/test-artifacts'), Path('target/a1-production'), Path('target/a2-production'),
         Path('target/a1-fg'), Path('target/a1-tags'), Path('target/a2-tags'),
         Path('target/a2-tags-review'), Path('target/a2-sample'), Path('target/audit-query-capacity.json')]
for root in roots:
    if not root.exists():
        continue
    for path in ([root] if root.is_file() else root.rglob('*')):
        if not path.is_file() or path.suffix.lower() not in {'.txt', '.xml', '.json', '.log'}:
            continue
        path.write_text(redact_text(path.read_text(errors='replace')))
print('Sanitized text test reports; screenshots and synthetic exports are unchanged.')
