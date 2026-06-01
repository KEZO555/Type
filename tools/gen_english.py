#!/usr/bin/env python3
"""
Build app/src/main/assets/en_words.txt — the bundled English word-frequency list used for word
prediction (prefix completion). "word<space>count" per line, lowercase a-z only, length 1..15,
top MAX_WORDS by frequency.

Source: a "word<TAB or space>count" list such as Norvig's count_1w.txt
(https://norvig.com/ngrams/count_1w.txt).

Usage:  python3 gen_english.py [count_1w.txt]
"""
import re, sys, os

MAX_WORDS = 30000
MIN_LEN, MAX_LEN = 1, 15
WORD = re.compile(r"^[a-z]+$")

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(here)
src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/count_1w.txt"
out = os.path.join(repo, "app/src/main/assets/en_words.txt")

kept = []
with open(src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0].lower(), parts[1]
        if not WORD.match(w) or not (MIN_LEN <= len(w) <= MAX_LEN):
            continue
        try:
            count = int(c)
        except ValueError:
            continue
        kept.append((w, count))
        if len(kept) >= MAX_WORDS:
            break   # the source is already sorted by descending frequency

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as fo:
    for w, c in kept:
        fo.write(f"{w} {c}\n")

print(f"wrote {out}  ({len(kept):,} words, {os.path.getsize(out):,} bytes)")
