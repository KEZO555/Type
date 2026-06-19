#!/usr/bin/env python3
"""
Build app/src/main/assets/en_words.txt — the bundled English word-frequency list used for autocorrect
and word prediction. "word<space>count" per line, lowercase a-z only, length 1..15, top MAX_WORDS by
frequency.

Source: SymSpell's frequency_dictionary_en_82_765.txt
(https://raw.githubusercontent.com/wolfgarbe/SymSpell/master/SymSpell/frequency_dictionary_en_82_765.txt).
It carries the same Google Web Trillion Word counts the list always used (so ranking is unchanged), but
the word set is SCOWL-curated — proper nouns, brand names, fragments and misspellings are already gone,
so the corrector no longer "fixes" typos onto junk (the same clean-up done for Hebrew). Contractions and
other non a-z entries are dropped to match how words are tokenised on device.

Usage:  python3 gen_english.py [frequency_dictionary_en_82_765.txt]
"""
import re, sys, os

MAX_WORDS = 70000
MIN_LEN, MAX_LEN = 1, 15
WORD = re.compile(r"^[a-z]+$")

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(here)
src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/sym_en.txt"
out = os.path.join(repo, "app/src/main/assets/en_words.txt")

# utf-8-sig strips the BOM the SymSpell file carries (so the first word isn't "﻿the").
rows = []
with open(src, encoding="utf-8-sig", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0].lower(), parts[1]
        if not WORD.match(w) or not (MIN_LEN <= len(w) <= MAX_LEN):
            continue
        try:
            rows.append((w, int(c)))
        except ValueError:
            continue

rows.sort(key=lambda x: -x[1])          # ensure descending frequency, then cap
kept = rows[:MAX_WORDS]

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as fo:
    for w, c in kept:
        fo.write(f"{w} {c}\n")

print(f"wrote {out}  ({len(kept):,} words, {os.path.getsize(out):,} bytes)")
