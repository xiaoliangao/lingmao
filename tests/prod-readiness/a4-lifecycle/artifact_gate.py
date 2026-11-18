"""Check actual release artifacts; test fixtures must remain outside both production outputs."""
from pathlib import Path
import json
import zipfile

root = Path(__file__).resolve().parents[3]
jar = root / 'target/scm-0.0.1-SNAPSHOT.jar'
dist = root / 'web/dist'
assert jar.is_file() and (dist / 'index.html').is_file(), 'build both release artifacts first'
with zipfile.ZipFile(jar) as archive:
    app = [name for name in archive.namelist() if name.startswith('BOOT-INF/classes/')]
    assert not any('/test/' in name or '/mock/' in name.lower() or 'application-it.' in name or 'Fixture' in name for name in app), 'test code packaged in JAR'
files = list(dist.rglob('*'))
for file in files:
    if file.is_file() and file.suffix in {'.html', '.js', '.json'}:
        text = file.read_text(errors='replace')
        assert not any(marker in text for marker in ['.a3-prod-readiness', 'SecurityFixture.vue', 'ExportFixture.vue', 'real-session.ts', 'mockServiceWorker.js']), 'test entry packaged in web dist'
output = root / 'target/test-artifacts/a4/artifact-results.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'passed': True, 'application_entries': len(app), 'web_files': sum(p.is_file() for p in files)}))
print('PASS production JAR and web dist exclude test fixtures and mock entry points')
