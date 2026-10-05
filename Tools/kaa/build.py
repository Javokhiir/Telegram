#!/usr/bin/env python3
"""Validate tr_XX.txt against src_XX.txt and write res/values-b+kaa/strings.xml."""
import glob, os, re, sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = '/Users/macstore.uz/Desktop/Telegram/TMessagesProj/src/main/res/values-b+kaa/strings.xml'

PH = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc]|%%|\\n|\*\*|un\d|<(?!!\[CDATA)[^>]+>|\{[^}]*\}')


def load(path):
    d = {}
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line:
            continue
        k, _, v = line.partition('\t')
        d[k] = v
    return d


def fix(v):
    v = re.sub(r"(?<!\\)'", r"\\'", v)
    v = re.sub(r'&(?!(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)', '&amp;', v)
    return v


errors, out = [], []
for src in sorted(glob.glob(os.path.join(HERE, 'src_*.txt'))):
    tr = src.replace('src_', 'tr_')
    if not os.path.exists(tr):
        continue
    s, t = load(src), load(tr)
    missing = [k for k in s if k not in t]
    if missing:
        errors.append('%s: missing %d keys, e.g. %s' % (os.path.basename(tr), len(missing), missing[:5]))
    for k, v in t.items():
        if k not in s:
            errors.append('%s: unknown key %s' % (os.path.basename(tr), k))
            continue
        v = fix(v)
        if Counter(PH.findall(s[k])) != Counter(PH.findall(v)):
            errors.append('%s: %s placeholders %s != %s' % (os.path.basename(tr), k, sorted(PH.findall(s[k])), sorted(PH.findall(v))))
            continue
        out.append((k, v))

# Strings added to the XML by hand (features newer than the src_XX snapshot) must survive a rewrite.
src_keys = set()
for src in glob.glob(os.path.join(HERE, 'src_*.txt')):
    src_keys.update(load(src))
extra = []
if os.path.exists(OUT):
    for m in re.finditer(r'    <string name="([^"]+)"([^>]*)>(.*?)</string>\n', open(OUT, encoding='utf-8').read(), re.S):
        if m.group(1) not in src_keys:
            extra.append(m.group(0))

for e in errors:
    print(e)
if '--write' in sys.argv:
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
        for k, v in out:
            f.write('    <string name="%s">%s</string>\n' % (k, v))
        for line in extra:
            f.write(line)
        f.write('</resources>\n')
print('ok=%d extra=%d errors=%d' % (len(out), len(extra), len(errors)))
