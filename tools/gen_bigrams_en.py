#!/usr/bin/env python3
"""
Build app/src/main/assets/en_bigrams.txt — the bundled English pre-trained next-word (bigram) model.
"word1 word2 count" per line, the exact format WordDictionary loads, so the keyboard predicts the next
word and biases autocorrect by context out of the box (not only after you've typed a lot).

Source: SymSpell's frequency_bigramdictionary_en_243_342.txt (Google Web corpus bigrams)
(https://raw.githubusercontent.com/wolfgarbe/SymSpell/master/SymSpell/frequency_bigramdictionary_en_243_342.txt).
Kept: both words a-z and present in the unigram dictionary (en_words.txt) so we never suggest an
out-of-vocabulary word; top MAX_PAIRS by count to bound memory.

Usage:  python3 gen_bigrams_en.py [frequency_bigramdictionary_en_243_342.txt]
"""
import re, sys, os

MAX_PAIRS = 60000
WORD = re.compile(r"^[a-z]+$")

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(here)
src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/sym_bi.txt"
words_path = os.path.join(repo, "app/src/main/assets/en_words.txt")
out = os.path.join(repo, "app/src/main/assets/en_bigrams.txt")

# in-vocabulary guard: only predict words the unigram dictionary already knows
vocab = set()
with open(words_path, encoding="utf-8") as f:
    for line in f:
        w = line.split(" ", 1)[0]
        if w:
            vocab.add(w)

rows = []
with open(src, encoding="utf-8-sig", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 3:
            continue
        w1, w2, c = parts[0].lower(), parts[1].lower(), parts[2]
        if not (WORD.match(w1) and WORD.match(w2)) or w1 not in vocab or w2 not in vocab:
            continue
        try:
            rows.append((w1, w2, int(c)))
        except ValueError:
            continue

rows.sort(key=lambda x: -x[2])
kept = rows[:MAX_PAIRS]

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as fo:
    for w1, w2, c in kept:
        fo.write(f"{w1} {w2} {c}\n")

prevs = len({w1 for w1, _, _ in kept})
print(f"wrote {out}  ({len(kept):,} pairs over {prevs:,} context words, {os.path.getsize(out):,} bytes)")
