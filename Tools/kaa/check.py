#!/usr/bin/env python3
"""Flags stray Cyrillic/Arabic letters in tr_XX.txt (the translation is Latin-script Karakalpak)."""
import re, sys
bad = re.compile('[Ѐ-ӿ؀-ۿ]')
for p in sys.argv[1:]:
    for i, line in enumerate(open(p, encoding='utf-8'), 1):
        if bad.search(line):
            print('%s:%d: %s' % (p, i, line.rstrip()))
