#!/usr/bin/env python3
"""Pure helper guards: no apt, network, PostgreSQL process or privileged writes."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('install-pg16-client.sh')
FINGERPRINT = 'B97B0AFCAA1A47F044F244A07FCC7D46ACCC4CF8'


class ClientInstallGuards(unittest.TestCase):
    def helper(self, function, value):
        return subprocess.run(['bash', '-c', 'source "$1"; "$2" "$3"',
                               'pg16-helper-test', str(SCRIPT), function, value],
                              text=True, capture_output=True, timeout=5)

    def test_official_primary_key(self):
        result = self.helper('pg16_verify_key', f'pub:::::::::\nfpr:::::::::{FINGERPRINT}:\n')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_wrong_empty_duplicate_and_additional_primary_keys(self):
        good = f'pub:::::::::\nfpr:::::::::{FINGERPRINT}:\n'
        bad = 'pub:::::::::\nfpr:::::::::0123456789ABCDEF0123456789ABCDEF01234567:\n'
        for metadata in ('', bad, good + bad, bad + good, good + good):
            with self.subTest(metadata=metadata):
                self.assertNotEqual(self.helper('pg16_verify_key', metadata).returncode, 0)

    def test_subkey_is_not_mistaken_for_primary_key(self):
        metadata = f'pub:::::::::\nfpr:::::::::{FINGERPRINT}:\nsub:::::::::\nfpr:::::::::SUBKEY:\n'
        self.assertEqual(self.helper('pg16_verify_key', metadata).returncode, 0)

    def clients(self, directory, versions):
        for client, (version, status) in versions.items():
            path = directory / client
            path.write_text('#!/bin/sh\nprintf "%s\\n" ' + shlex.quote(version) + f'\nexit {status}\n')
            path.chmod(0o700)

    def test_each_client_must_be_major_16_and_exit_successfully(self):
        good = {client: (f'{client} (PostgreSQL) 16.16 (Ubuntu 16.16-1.pgdg26.04+1)', 0)
                for client in ('psql', 'pg_dump', 'pg_restore')}
        with tempfile.TemporaryDirectory(prefix='pg16-helper-') as temp:
            directory = Path(temp)
            self.clients(directory, good)
            self.assertEqual(self.helper('pg16_verify_clients', temp).returncode, 0)
            for client in good:
                for version, status in ((f'{client} (PostgreSQL) 18.1', 0),
                                        (f'{client} (PostgreSQL) 160.1', 0),
                                        (f'{client} (PostgreSQL) 16.16', 1), ('', 0)):
                    with self.subTest(client=client, version=version, status=status):
                        self.clients(directory, {**good, client: (version, status)})
                        self.assertNotEqual(self.helper('pg16_verify_clients', temp).returncode, 0)
            self.clients(directory, good)
            (directory / 'pg_restore').unlink()
            self.assertNotEqual(self.helper('pg16_verify_clients', temp).returncode, 0)

    def test_direct_install_fails_before_mutation_outside_actions(self):
        env = os.environ.copy()
        env.pop('GITHUB_ACTIONS', None)
        result = subprocess.run(['bash', str(SCRIPT)], env=env, text=True,
                                capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('restricted to the disposable Linux GitHub Actions runner', result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
