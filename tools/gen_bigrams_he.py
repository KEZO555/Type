#!/usr/bin/env python3
"""
Build dicts/he_bigrams.txt — the downloadable Hebrew pre-trained next-word (bigram) model.
"word1 word2 count" per line, the exact format WordDictionary loads, so the keyboard predicts the next
Hebrew word and biases autocorrect by context out of the box.

Source: Universal Dependencies Hebrew treebanks (modern Hebrew, license-clean, reachable on GitHub):
  - UD_Hebrew-HTB           he_htb-ud-{train,dev,test}.conllu         (newswire)
  - UD_Hebrew-IAHLTknesset  he_iahltknesset-ud-{train,dev,test}.conllu (parliamentary speech)
  - UD_Hebrew-IAHLTwiki     he_iahltwiki-ud-{train,dev,test}.conllu    (encyclopedic / Wikipedia)
The treebanks are morphologically segmented, so we read the GLUED surface forms from the multiword-token
ranges (so "בבית"/"ושלום" stay whole, matching what people type, not the split "ב בית"/"ו שלום").

Kept: bigrams of Hebrew-letter surface words that are both in the unigram dictionary (dicts/he.txt),
seen at least MIN_COUNT times, top MAX_PAIRS by count.

Usage:  python3 gen_bigrams_he.py [conllu ...]   (defaults: the nine /tmp/*.conllu below)
"""
import re, sys, os
from collections import Counter

MIN_COUNT = 2
MAX_PAIRS = 40000
HE = re.compile(r"^[א-ת]+$")

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(here)
srcs = sys.argv[1:] or [
    "/tmp/he_htb_train.conllu", "/tmp/he_htb_dev.conllu", "/tmp/he_htb_test.conllu",
    "/tmp/he_kns_train.conllu", "/tmp/he_kns_dev.conllu", "/tmp/he_kns_test.conllu",
    "/tmp/he_wiki_train.conllu", "/tmp/he_wiki_dev.conllu", "/tmp/he_wiki_test.conllu",
]
words_path = os.path.join(repo, "dicts/he.txt")
out = os.path.join(repo, "dicts/he_bigrams.txt")

vocab = set()
with open(words_path, encoding="utf-8") as f:
    for line in f:
        w = line.split(" ", 1)[0]
        if w:
            vocab.add(w)


def surface_tokens(path):
    """Glued surface words per sentence (multiword-token ranges), with None at sentence breaks."""
    skip_until = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                skip_until = 0
                yield None
                continue
            cols = line.split("\t")
            if len(cols) < 2:
                continue
            tid = cols[0]
            if "-" in tid:                       # multiword token → use the glued surface form
                skip_until = int(tid.split("-")[1])
                yield cols[1]
                continue
            if "." in tid:                       # empty node
                continue
            if int(tid) <= skip_until:           # morpheme already covered by a range
                continue
            yield cols[1]


big = Counter()
for path in srcs:
    prev = None
    for w in surface_tokens(path):
        if w is None or not HE.match(w) or w not in vocab:
            prev = None
            continue
        if prev is not None:
            big[(prev, w)] += 1
        prev = w

kept = [(a, b, c) for (a, b), c in big.items() if c >= MIN_COUNT]
kept.sort(key=lambda x: -x[2])
kept = kept[:MAX_PAIRS]

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as fo:
    for a, b, c in kept:
        fo.write(f"{a} {b} {c}\n")

prevs = len({a for a, _, _ in kept})
print(f"wrote {out}  ({len(kept):,} pairs over {prevs:,} context words, {os.path.getsize(out):,} bytes)")
