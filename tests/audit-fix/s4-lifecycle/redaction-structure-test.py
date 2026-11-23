"""No-service regressions: keep audit JSON valid and financial number bytes exact."""
import html
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('ci_redaction_under_test', ROOT / 'dev/ci/redaction.py')
redaction = importlib.util.module_from_spec(spec)
spec.loader.exec_module(redaction)


class StructuredRedactionTest(unittest.TestCase):
    def test_actual_data_log_shape_keeps_soft_delete_diff_and_exact_numbers(self):
        # The same business shape that invalidated the real Sample UI artifacts.
        value = '''{
  "rowId": "9223372036854775807",
  "diff": {
    "deleted_token": {"new": "0", "old": null},
    "est_cost": {"new": 999999999999999999.9900, "old": 0.000000000000000001}
  }
}'''
        self.assertEqual(redaction.redact_text(value), value)
        self.assertEqual(json.loads(redaction.redact_text(value))['diff']['deleted_token'],
                         {'new': '0', 'old': None})

    def test_json_credentials_of_every_value_type_are_entirely_replaced(self):
        values = [{'inner': 'OBJECT_SENTINEL', 'more': ['NESTED_SENTINEL']},
                  ['ARRAY_SENTINEL', {'more': 'NESTED_SENTINEL'}],
                  12345678901234567890, 12345.625, True, False, None, 'STRING_SENTINEL']
        for key in ('token', 'access_token', 'refreshToken', 'db.password', 'client_secret',
                    'authorization', 'cookie', 'set-cookie', 'scm_refresh', 'scm_browser'):
            for secret in values:
                with self.subTest(key=key, value_type=type(secret).__name__):
                    result = redaction.redact_text(json.dumps({key: secret, 'public': 'visible'}))
                    self.assertEqual(json.loads(result), {key: '[REDACTED]', 'public': 'visible'})

    def test_duplicate_and_unicode_escaped_credential_keys_cannot_bypass_redaction(self):
        value = r'{"token":{"a":"FIRST_SENTINEL"},"\u0074oken":["SECOND_SENTINEL"],"public":1}'
        result = redaction.redact_text(value)
        self.assertNotIn('SENTINEL', result)
        self.assertEqual(result.count('"[REDACTED]"'), 2)
        self.assertEqual(json.loads(result)['public'], 1)

    def test_only_real_soft_delete_value_shapes_are_exempt(self):
        invalid = ['CREDENTIAL_SENTINEL', -1, True, 1.5, 9223372036854775808,
                   '9223372036854775808', '01', {'old': None, 'new': 'CREDENTIAL_SENTINEL'},
                   {'old': None, 'new': '0', 'secret': 'EXTRA_SENTINEL'}, ['0']]
        for value in invalid:
            with self.subTest(value_type=type(value).__name__):
                result = json.loads(redaction.redact_text(json.dumps({'deleted_token': value})))
                self.assertEqual(result['deleted_token'], '[REDACTED]')

    def test_valid_soft_delete_values_keep_original_bytes_without_exempting_other_tokens(self):
        valid = [None, 0, '0', 9223372036854775807, '9223372036854775807',
                 {'old': '0', 'new': '9223372036854775807'}, {'old': None, 'new': '0'}]
        for value in valid:
            with self.subTest(value_type=type(value).__name__):
                original = json.dumps({'deleted_token': value}, indent=2)
                self.assertEqual(redaction.redact_text(original), original)
        for key in ('session_token', 'deleted_access_token', 'upload_token'):
            self.assertEqual(json.loads(redaction.redact_text(json.dumps({key: '0'})))[key], '[REDACTED]')
        xml = '<suite><deleted_token>0</deleted_token><property name="deleted_token" value="9223372036854775807"/></suite>'
        self.assertEqual(redaction.redact_text(xml), xml)

    def test_duplicate_soft_delete_diff_members_cannot_hide_a_credential(self):
        source = '{"deleted_token":{"old":"HIDDEN_SENTINEL","old":"0","new":"0"}}'
        self.assertEqual(json.loads(redaction.redact_text(source)), {'deleted_token': '[REDACTED]'})

    def test_nested_serialized_json_and_string_log_payloads_stay_valid(self):
        inner = '{"secret": {"value":"NESTED_SENTINEL"},"deleted_token":{"old":null,"new":"0"},"sum":999999999999999999.9900}'
        source = json.dumps({'message': json.dumps({'payload': inner}),
                             'log': 'INFO request {"token":["LOG_SENTINEL"],"public":7} done'})
        first = json.loads(redaction.redact_text(source))
        second = json.loads(first['message'])
        third = json.loads(second['payload'])
        self.assertEqual(third['secret'], '[REDACTED]')
        self.assertEqual(third['deleted_token'], {'old': None, 'new': '0'})
        self.assertIn('999999999999999999.9900', second['payload'])
        self.assertEqual(json.loads(first['log'][13:-5]), {'token': '[REDACTED]', 'public': 7})
        self.assertNotIn('SENTINEL', json.dumps(first))

    def test_json_fragments_in_log_lines_keep_each_document_valid(self):
        value = 'INFO {"token":{"nested":"ONE_SENTINEL"},"deleted_token":0}\nINFO [{"password":false,"public":2}]\n'
        result = redaction.redact_text(value)
        self.assertEqual(json.loads(result.splitlines()[0][5:]), {'token': '[REDACTED]', 'deleted_token': 0})
        self.assertEqual(json.loads(result.splitlines()[1][5:]), [{'password': '[REDACTED]', 'public': 2}])

    def test_plaintext_compound_credentials_remove_the_whole_value(self):
        for payload in ('{"one":"FIRST_SENTINEL","two":["LAST_SENTINEL"]}',
                        '["FIRST_SENTINEL",{"two":"LAST_SENTINEL"}]'):
            result = redaction.redact_text('token=' + payload + ' public=visible')
            self.assertNotIn('SENTINEL', result)
            self.assertIn('public=visible', result)
        self.assertEqual(redaction.redact_text('deleted_token=0 public=visible'), 'deleted_token=0 public=visible')

    def test_xml_attributes_elements_and_escaped_assertion_strings_remain_safe(self):
        raw = json.dumps({'token': 'XML_SENTINEL " OTHER_SENTINEL', 'public': 'visible'})
        source = '<suite><property name="DB_PASSWORD" value="PROP_SENTINEL"/>' \
                 '<token>ELEMENT_SENTINEL</token><failure message="' + html.escape(raw, quote=True) + '"/></suite>'
        result = redaction.redact_text(source)
        ET.fromstring(result)
        self.assertNotIn('SENTINEL', result)
        self.assertIn('visible', result)

    def test_xml_embedded_json_containers_stay_parseable_in_attributes_and_text(self):
        payload = json.dumps({'token': {'value': 'XML_OBJECT_SENTINEL'},
                              'deleted_token': {'old': None, 'new': '0'}, 'public': 7})
        source = '<suite><failure message="' + html.escape(payload, quote=True) \
                 + '"/><system-out>' + html.escape(payload, quote=True) + '</system-out></suite>'
        result = redaction.redact_text(source)
        tree = ET.fromstring(result)
        for text in (tree.find('failure').attrib['message'], tree.find('system-out').text):
            self.assertEqual(json.loads(text), {'token': '[REDACTED]',
                                               'deleted_token': {'old': None, 'new': '0'}, 'public': 7})
        self.assertNotIn('SENTINEL', result)

    def test_real_artifact_entrypoint_preserves_json_and_exact_decimal_bytes(self):
        value = '{"deleted_token":{"old":null,"new":"0"},"amount":999999999999999999.9900,"password":{"nested":"ENTRY_SENTINEL"}}'
        expected = value.replace('{"nested":"ENTRY_SENTINEL"}', '"[REDACTED]"')
        with tempfile.TemporaryDirectory(prefix='scm-structured-redaction-') as directory:
            output = Path(directory) / 'target/failsafe-reports/evidence.json'
            output.parent.mkdir(parents=True)
            output.write_text(value)
            for _ in range(2):
                subprocess.run([sys.executable, str(ROOT / 'dev/ci/sanitize-artifacts.py')], cwd=directory,
                               check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
                self.assertEqual(output.read_text(), expected)
                self.assertEqual(json.loads(output.read_text())['password'], '[REDACTED]')

    def test_xml_cdata_comments_and_credential_attributes_remain_protected(self):
        source = '<suite token="ATTRIBUTE_SENTINEL"><!-- password=COMMENT_SENTINEL -->' \
                 '<system-out><![CDATA[{"secret":["CDATA_SENTINEL"],"public":1}]]></system-out></suite>'
        result = redaction.redact_text(source)
        tree = ET.fromstring(result)
        self.assertNotIn('SENTINEL', result)
        self.assertEqual(tree.attrib['token'], '[REDACTED]')
        self.assertEqual(json.loads(tree.find('system-out').text), {'secret': '[REDACTED]', 'public': 1})

    def test_json_escaped_text_retains_existing_cookie_and_quoted_value_guards(self):
        source = r'payload={\"password\":\"ESCAPED_SENTINEL \\\" OTHER_SENTINEL\",\"public\":\"visible\"}'
        result = redaction.redact_text(source)
        self.assertNotIn('SENTINEL', result)
        self.assertIn('visible', result)
        headers = 'Cookie: COOKIE_SENTINEL; scm_refresh=REFRESH_SENTINEL\nAuthorization: Bearer AUTH_SENTINEL\npublic=visible'
        self.assertNotIn('SENTINEL', redaction.redact_text(headers))

    def test_known_environment_credentials_are_masked_inside_json_strings(self):
        secret = 'ENV_SENTINEL " \\ & two words'
        with patch.dict(os.environ, {'SCM_CI_DB_PASSWORD': secret}):
            value = json.dumps({'message': secret, 'nested': json.dumps({'text': secret}), 'public': 'visible'})
            result = json.loads(redaction.redact_text(value))
            self.assertEqual(result['message'], '[REDACTED]')
            self.assertEqual(json.loads(result['nested'])['text'], '[REDACTED]')
            self.assertEqual(result['public'], 'visible')
        with patch.dict(os.environ, {'SCM_CI_DB_PASSWORD': '12345678901234567890'}):
            self.assertEqual(json.loads(redaction.redact_text('{"message":"12345678901234567890"}')),
                             {'message': '[REDACTED]'})

    def test_numbers_and_layout_are_not_round_tripped_through_binary_float(self):
        value = ' { "id": 9223372036854775807, "amount": 999999999999999999.9900, "small": 1.25000e-123, "zero": -0.00, "nullable": null }\n'
        self.assertEqual(redaction.redact_text(value), value)
        for token in ('9223372036854775807', '999999999999999999.9900', '1.25000e-123', '-0.00'):
            self.assertIn(token, redaction.redact_text(value))

    def test_redaction_is_idempotent_for_structured_and_plain_reports(self):
        examples = [json.dumps({'token': {'v': 'OBJECT_SENTINEL'}, 'deleted_token': {'old': None, 'new': '0'},
                                'message': json.dumps({'password': ['NESTED_SENTINEL']})}),
                    'INFO {"token":false,"public":7}\nCookie: COOKIE_SENTINEL\n',
                    '<token>XML_SENTINEL</token>', 'token=["ARRAY_SENTINEL"] public=visible']
        for source in examples:
            with self.subTest(format=source[:5]):
                first = redaction.redact_text(source)
                self.assertEqual(redaction.redact_text(first), first)

    def test_malformed_quoted_credentials_fail_closed_and_jwts_are_masked(self):
        self.assertNotIn('SENTINEL', redaction.redact_text('password="BROKEN_SENTINEL rest'))
        value = json.dumps({'public': 'Bearer eyJheader.claims.signature', 'token': None})
        result = json.loads(redaction.redact_text(value))
        self.assertEqual(result['public'], 'Bearer [REDACTED JWT]')
        self.assertEqual(result['token'], '[REDACTED]')


if __name__ == '__main__':
    unittest.main(verbosity=2)
