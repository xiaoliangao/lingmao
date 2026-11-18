"""Redact credential values in plain, JSON-escaped and XML-escaped CI text."""
import html
import json
import os
import re

_KEY = (r'(?:[a-z][a-z0-9_.-]*[._-])?(?:password|passwd|secret|token)|'
        r'app[_-]?secret|(?:access|refresh)[_-]?token|jwt[_-]?secret|'
        r'file[_-]?sign[_-]?key|init[_-]?admin[_-]?password|authorization|set-cookie|cookie|'
        r'scm_(?:refresh|browser)')
_QUOTE = re.compile(r'''\\*(?:["']|&(?:quot|apos|\#34|\#39|\#x22|\#x27);)''', re.I)
_FIELD = re.compile(r'(?<![a-z0-9_-])(?:' + _QUOTE.pattern + r')?('
                    + _KEY + r')(?:' + _QUOTE.pattern + r')?\s*[:=]\s*', re.I)
_JWT = re.compile(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b')
_XML_PROPERTY = re.compile(r'(<property\b[^>]*\bname=["\'](?:' + _KEY
                           + r')["\'][^>]*\bvalue=)(["\'])(.*?)\2', re.I | re.S)
_XML_ELEMENT = re.compile(r'(<(' + _KEY + r')\b[^>]*>).*?(</\2\s*>)', re.I | re.S)
_ENV_KEYS = ('SCM_CI_DB_PASSWORD', 'SCM_CI_REDIS_PASSWORD', 'JWT_SECRET', 'FILE_SIGN_KEY',
             'INIT_ADMIN_PASSWORD', 'SCM_UI_PASSWORD', 'DB_PASSWORD', 'REDIS_PASSWORD')


def _quoted_end(value, start, delimiter):
    """Find a matching quote without mistaking a nested JSON escape for its end."""
    cursor = start + len(delimiter)
    level = len(delimiter) - len(delimiter.lstrip('\\'))
    while True:
        end = value.find(delimiter, cursor)
        if end < 0:
            return len(value), ''  # A malformed quoted value fails closed.
        quote_at = end + level
        preceding = quote_at
        while preceding > start and value[preceding - 1] == '\\':
            preceding -= 1
        if (quote_at - preceding) % (2 * (level + 1)) == level:
            return end, delimiter
        cursor = quote_at + 1


def redact_text(value):
    for key in _ENV_KEYS:
        secret = os.environ.get(key, '')
        if secret:
            for encoded in {secret, html.escape(secret, quote=True), json.dumps(secret)[1:-1]}:
                value = value.replace(encoded, '[REDACTED]')
    value = _XML_PROPERTY.sub(lambda match: match[1] + match[2] + '[REDACTED]' + match[2], value)
    value = _XML_ELEMENT.sub(lambda match: match[1] + '[REDACTED]' + match[3], value)
    parts, cursor = [], 0
    while match := _FIELD.search(value, cursor):
        start = match.end()
        parts.append(value[cursor:start])
        quote = _QUOTE.match(value, start)
        if quote:
            delimiter = quote[0]
            end, closing = _quoted_end(value, start, delimiter)
            parts.append(delimiter + '[REDACTED]' + closing)
            cursor = end + len(closing)
        else:
            # A bare HTTP header owns the remainder of its line. Starting at the
            # first header also removes same-line Cookie/Authorization tails.
            is_header = match[1].lower() in {'authorization', 'set-cookie', 'cookie'}
            if not is_header and value.startswith('[REDACTED]', start):
                cursor = start + len('[REDACTED]')
            else:
                boundary = r'[\r\n]' if is_header else r'[\s,;<\]}>]'
                end = re.search(boundary, value[start:])
                cursor = start + end.start() if end else len(value)
            parts.append('[REDACTED]')
    parts.append(value[cursor:])
    return _JWT.sub('[REDACTED JWT]', ''.join(parts))
