"""Redact CI credentials without changing audit JSON structure or number bytes."""
from decimal import Decimal
import html
import json
import os
import re
import xml.etree.ElementTree as ET

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
_SECRET_KEY = re.compile(r'(?:' + _KEY + r')\Z', re.I)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            return object()  # Duplicate fields cannot satisfy a business-value exemption.
        result[key] = value
    return result


_DECODER = json.JSONDecoder(parse_float=Decimal, object_pairs_hook=_unique_object)
_SPACE = re.compile(r'\s*')
_JSON_START = re.compile(r'[\[{]')
_XML_TAG = re.compile(r'<[A-Za-z][^<>]*>', re.S)
_XML_ATTRIBUTE = re.compile(r'(\b[\w:.-]+\s*=\s*)(["\'])(.*?)\2', re.S)


def _soft_delete_value(key, value):
    """The DB's noncredential tombstone is 0 or a positive signed-64-bit row ID.

    A DataLog diff has exactly old/new. No other *_token key, arbitrary string,
    boolean, overflow, or object with extra fields receives this exemption.
    """
    def scalar(item):
        if item is None:
            return True
        if type(item) is int:
            return 0 <= item <= 9223372036854775807
        return (type(item) is str and len(item) <= 19
                and re.fullmatch(r'0|[1-9][0-9]*', item) is not None
                and int(item) <= 9223372036854775807)

    return key.lower() == 'deleted_token' and (scalar(value) or (
        type(value) is dict and set(value) == {'old', 'new'}
        and all(scalar(item) for item in value.values())))


def _mask_literals(value):
    for key in _ENV_KEYS:
        secret = os.environ.get(key, '')
        if secret:
            for encoded in sorted({secret, html.escape(secret, quote=True), json.dumps(secret)[1:-1]},
                                  key=len, reverse=True):
                value = value.replace(encoded, '[REDACTED]')
    return _JWT.sub('[REDACTED JWT]', value)


def _json_document(value, start, end, depth):
    """Rewrite credential spans only; never dump the surrounding numeric values.

    The caller has parsed this complete JSON value. Walking its original text
    also keeps duplicate keys, whitespace, exponents and Decimal trailing zeros.
    """
    changes = []

    def walk(cursor, key=None):
        cursor = _SPACE.match(value, cursor).end()
        if key is not None and _SECRET_KEY.fullmatch(key):
            parsed, finish = _DECODER.raw_decode(value, cursor)
            if not _soft_delete_value(key, parsed):
                changes.append((cursor, finish, '"[REDACTED]"'))
            return finish
        if value[cursor] == '{':
            cursor = _SPACE.match(value, cursor + 1).end()
            if value[cursor] == '}':
                return cursor + 1
            while True:
                name, key_end = _DECODER.raw_decode(value, cursor)
                colon = _SPACE.match(value, key_end).end()
                cursor = _SPACE.match(value, walk(colon + 1, name)).end()
                if value[cursor] == '}':
                    return cursor + 1
                cursor = _SPACE.match(value, cursor + 1).end()
        if value[cursor] == '[':
            cursor = _SPACE.match(value, cursor + 1).end()
            if value[cursor] == ']':
                return cursor + 1
            while True:
                cursor = _SPACE.match(value, walk(cursor)).end()
                if value[cursor] == ']':
                    return cursor + 1
                cursor = _SPACE.match(value, cursor + 1).end()
        parsed, finish = _DECODER.raw_decode(value, cursor)
        if isinstance(parsed, str):
            redacted = _redact(parsed, depth + 1)
            if redacted != parsed:
                changes.append((cursor, finish, json.dumps(redacted, ensure_ascii=False)))
        return finish

    walk(start)
    result, cursor = [], start
    for begin, finish, replacement in changes:
        result.extend((value[cursor:begin], replacement))
        cursor = finish
    result.append(value[cursor:end])
    return ''.join(result)


def _next_json(value, start):
    for match in _JSON_START.finditer(value, start):
        try:
            _, end = _DECODER.raw_decode(value, match.start())
            return match.start(), end
        except (ValueError, RecursionError):
            continue
    return None


def _xml_text(value, depth):
    # Decode only XML attribute/text payloads, not the document delimiters.
    # This lets a nested JSON credential object be replaced before escaping it
    # back into an assertion attribute or system-out element.
    def attribute(match):
        inner = html.unescape(match[3])
        key = match[1].split('=', 1)[0].strip()
        redacted = ('[REDACTED]' if _SECRET_KEY.fullmatch(key) and not business_text(key, inner)
                    else _redact(inner, depth + 1))
        return (match[0] if redacted == inner else
                match[1] + match[2] + html.escape(redacted, quote=True) + match[2])

    def content(match):
        inner = html.unescape(match[0])
        redacted = _redact(inner, depth + 1)
        return match[0] if redacted == inner else html.escape(redacted, quote=False)

    def business_text(key, text):
        try:
            parsed = _DECODER.decode(html.unescape(text))
        except (ValueError, RecursionError):
            parsed = html.unescape(text)
        return _soft_delete_value(key, parsed)

    def property_value(match):
        key = re.search(r'\bname=["\']([^"\']+)', match[1], re.I)[1]
        return (match[0] if business_text(key, match[3]) else
                match[1] + match[2] + '[REDACTED]' + match[2])

    value = _XML_PROPERTY.sub(property_value, value)
    value = _XML_ELEMENT.sub(lambda match: match[0] if business_text(match[2], match[0][len(match[1]):-len(match[3])])
                             else match[1] + '[REDACTED]' + match[3], value)
    value = re.sub(r'(<!\[CDATA\[)(.*?)(\]\]>)',
                   lambda match: match[1] + _redact(match[2], depth + 1) + match[3], value, flags=re.S)
    value = re.sub(r'(<!--)(.*?)(-->)',
                   lambda match: match[1] + _redact(match[2], depth + 1) + match[3], value, flags=re.S)
    value = _XML_TAG.sub(lambda match: _XML_ATTRIBUTE.sub(attribute, match[0]), value)
    return re.sub(r'(?<=>)[^<]+(?=<)', content, value)


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


def _plain_text(value, depth):
    value = _xml_text(value, depth)
    parts, cursor = [], 0
    fragment = _next_json(value, cursor)
    while cursor < len(value):
        match = _FIELD.search(value, cursor)
        if fragment is not None and fragment[0] < cursor:
            fragment = _next_json(value, cursor)
        if fragment is not None and (match is None or fragment[0] < match.start()):
            begin, end = fragment
            parts.extend((_mask_literals(value[cursor:begin]), _json_document(value, begin, end, depth)))
            cursor = end
            continue
        if match is None:
            parts.append(_mask_literals(value[cursor:]))
            break
        start = match.end()
        parts.append(_mask_literals(value[cursor:start]))
        quote = _QUOTE.match(value, start)
        if quote:
            delimiter = quote[0]
            end, closing = _quoted_end(value, start, delimiter)
            inner = value[start + len(delimiter):end]
            parts.append(delimiter + (inner if closing and _soft_delete_value(match[1], inner)
                                      else '[REDACTED]') + closing)
            cursor = end + len(closing)
        else:
            # A bare HTTP header owns the remainder of its line. Starting at the
            # first header also removes same-line Cookie/Authorization tails.
            is_header = match[1].lower() in {'authorization', 'set-cookie', 'cookie'}
            if not is_header:
                try:
                    parsed, end = _DECODER.raw_decode(value, start)
                    if end == len(value) or value[end] in ' \t\r\n,;<]}>':
                        parts.append(value[start:end] if _soft_delete_value(match[1], parsed) else '[REDACTED]')
                        cursor = end
                        continue
                except (ValueError, RecursionError):
                    pass
                if value[start:start + 1] in ('{', '[') and not value.startswith('[REDACTED', start):
                    # An unterminated compound credential must not leak its tail.
                    parts.append('[REDACTED]')
                    break
            if not is_header and value.startswith('[REDACTED]', start):
                cursor = start + len('[REDACTED]')
            else:
                boundary = r'[\r\n]' if is_header else r'[\s,;<\]}>]'
                end = re.search(boundary, value[start:])
                cursor = start + end.start() if end else len(value)
            parts.append('[REDACTED]')
    return ''.join(parts)


def _redact(value, depth):
    if depth > 24:
        return '[REDACTED NESTED VALUE]'
    if value and any(value == os.environ.get(key) for key in _ENV_KEYS):
        return '[REDACTED]'
    start = _SPACE.match(value).end()
    try:
        _, end = _DECODER.raw_decode(value, start)
        if not value[end:].strip():
            return value[:start] + _json_document(value, start, end, depth) + value[end:]
    except (ValueError, RecursionError):
        pass
    if value.lstrip().startswith('<'):
        try:
            ET.fromstring(value)
            return _mask_literals(_xml_text(value, depth))
        except ET.ParseError:
            pass
    return _plain_text(value, depth)


def redact_text(value):
    return _redact(value, 0)
