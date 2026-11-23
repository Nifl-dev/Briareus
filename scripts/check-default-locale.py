#!/usr/bin/env python3
"""Fail when a DEFAULT resource bucket (values/) contains CJK.

Why: Android resource resolution falls back from a missing locale directly to
the unqualified values/ bucket. If values/ is Chinese, then EVERY locale
without a full translation renders Chinese -- Swedish, German, Polish. That is
not a missing translation, it is a WRONG DEFAULT, and it is invisible to the
Kotlin-literal scanner because the text lives in XML.

Rules:
  * values/strings.xml            MUST NOT contain CJK.  (fatal)
  * values-XX/strings.xml for non-Chinese XX MUST NOT contain CJK values.
  * CJK inside XML comments is ignored (upstream section headers).

Two escape hatches, deliberately different in character:
  * scripts/default-locale-allowlist.txt  -- DELIBERATE CJK. A proper-noun
    regulation name, a TTS preset id, a measuring glyph. Hand-curated, with a
    reason per line. Adding a line here is a product decision.
  * scripts/default-locale-baseline.txt   -- INHERITED leaks, frozen so the
    gate can catch NEW ones. Should only ever shrink. Regenerate with --update.

Usage:
    python3 scripts/check-default-locale.py            # gate
    python3 scripts/check-default-locale.py --update   # rewrite baseline
    python3 scripts/check-default-locale.py --all      # list everything

Exit: 0 clean, 1 new violation, 2 tool error.
"""
import os
import re
import sys

CJK = re.compile(r'[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]')
CHINESE_LOCALE = re.compile(r'^values-(zh|b\+zh)', re.IGNORECASE)
SKIP_DIRS = {'.git', 'build', '.cxx', '.gradle', 'generated'}

ALLOWLIST = 'scripts/default-locale-allowlist.txt'
BASELINE = 'scripts/default-locale-baseline.txt'


def strip_xml_comments(text):
    return re.sub(r'<!--.*?-->', '', text, flags=re.DOTALL)


def cjk_strings(path):
    """Yield (string_name, lineno, snippet) for CJK-bearing string values."""
    with open(path, encoding='utf-8', errors='ignore') as handle:
        raw = handle.read()
    cleaned = strip_xml_comments(raw)
    current = '?'
    for idx, line in enumerate(cleaned.split(chr(10)), start=1):
        m = re.search(r'<string name="([^"]+)"', line)
        if m:
            current = m.group(1)
        if CJK.search(line):
            yield current, idx, line.strip()[:100]


def find_res_dirs():
    out = []
    for root, dirs, _files in os.walk('.'):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        if os.path.basename(root) == 'res':
            out.append(root)
    return sorted(out)


def load_set(path):
    if not os.path.exists(path):
        return set()
    out = set()
    with open(path, encoding='utf-8') as handle:
        for line in handle:
            line = line.split('#', 1)[0].strip()
            if line:
                out.add(line)
    return out


def collect():
    """Return list of dicts describing every CJK-bearing non-Chinese resource."""
    findings = []
    checked = 0
    for res in find_res_dirs():
        for entry in sorted(os.listdir(res)):
            if not entry.startswith('values'):
                continue
            path = os.path.join(res, entry, 'strings.xml')
            if not os.path.isfile(path):
                continue
            checked += 1
            if CHINESE_LOCALE.match(entry):
                continue
            for name, lineno, snippet in cjk_strings(path):
                findings.append({
                    'dir': entry,
                    'path': path,
                    'name': name,
                    'line': lineno,
                    'snippet': snippet,
                    'key': '%s:%s' % (entry, name),
                    'default': entry == 'values',
                })
    return findings, checked


def main():
    findings, checked = collect()
    if not checked:
        print('error: no strings.xml found (run from repo root)', file=sys.stderr)
        return 2

    allow = load_set(ALLOWLIST)
    baseline = load_set(BASELINE)

    if '--update' in sys.argv:
        keys = sorted({f['key'] for f in findings if f['key'] not in allow})
        with open(BASELINE, 'w', encoding='utf-8') as handle:
            handle.write('# Inherited CJK in non-Chinese resource buckets, frozen 2026-11-23.\n')
            handle.write('# Format: <resource-dir>:<string-name>\n')
            handle.write('# This list should only SHRINK. Delete a line when the string\n')
            handle.write('# gets translated; a new line means a new leak got in.\n')
            handle.write('# Regenerate with: python3 scripts/check-default-locale.py --update\n')
            handle.write(chr(10).join(keys) + chr(10))
        print('baseline written: %d entries -> %s' % (len(keys), BASELINE))
        return 0

    allowed = [f for f in findings if f['key'] in allow]
    inherited = [f for f in findings if f['key'] not in allow and f['key'] in baseline]
    new = [f for f in findings if f['key'] not in allow and f['key'] not in baseline]

    if '--all' in sys.argv:
        for group, label in ((new, 'NEW'), (inherited, 'INHERITED'),
                             (allowed, 'ALLOWED')):
            if group:
                print('=== %s (%d) ===' % (label, len(group)))
                for f in group:
                    print('  %-16s %s' % (f['dir'], f['name']))
                print()

    if new:
        print('=== %d NEW CJK violation(s) in a non-Chinese resource bucket ===' % len(new))
        for f in new:
            tag = 'DEFAULT (fatal)' if f['default'] else 'locale'
            print('%s  %s:%d  %s' % (tag, f['path'], f['line'], f['name']))
            print('    %s' % f['snippet'])
        print()
        print('Fix the string, or add "<dir>:<name>" to %s with a reason.' % ALLOWLIST)
        print()

    resolved = sorted(baseline - {f['key'] for f in findings})
    if resolved:
        print('%d baseline entr(y/ies) no longer present -- run --update to drop them:' % len(resolved))
        for k in resolved[:10]:
            print('  - %s' % k)
        print()

    defaults = [f for f in findings if f['default'] and f['key'] not in allow]
    print('default-locale gate: %d strings.xml checked | %d default-bucket CJK | '
          '%d allowlisted | %d inherited | %d NEW'
          % (checked, len(defaults), len(allowed), len(inherited), len(new)))
    return 1 if new else 0


if __name__ == '__main__':
    sys.exit(main())
