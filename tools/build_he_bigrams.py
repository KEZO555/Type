#!/usr/bin/env python3
"""
Build Type's Hebrew next-word (bigram) model — dicts/he_bigrams.txt.

The keyboard's context features (contextCorrect: "picks the wrong valid word", the suggestion
bar, and completion re-ranking) are driven by a "prev next count" bigram list. This tool builds
that list from one or more Hebrew corpora and writes it in the exact on-device format.

Register matters: the model should match how people actually type. For a phone keyboard, a
*conversational* corpus (OpenSubtitles) beats formal text (news / legal / Wikipedia), which is
what a treebank-only model over-represents.

Corpora (what dicts/he_bigrams.txt is actually built from):
  • OpenSubtitles (conversational, the bulk of the model):
      https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/he.txt.gz
  • UD Hebrew treebanks (modern, gold word-segmentation — newswire, wiki, parliamentary):
      https://raw.githubusercontent.com/UniversalDependencies/UD_Hebrew-HTB/master/he_htb-ud-{train,dev,test}.conllu
      https://raw.githubusercontent.com/UniversalDependencies/UD_Hebrew-IAHLTwiki/master/he_iahltwiki-ud-{train,dev,test}.conllu
      https://raw.githubusercontent.com/UniversalDependencies/UD_Hebrew-IAHLTknesset/master/he_iahltknesset-ud-{train,dev,test}.conllu

Inputs may be plain text (one line per sentence/paragraph), .conllu (uses the "# text = " lines,
which carry the true surface forms a user types — proclitics glued on), or gzip (.gz) of either.

Usage:
  curl -o he.txt.gz https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/he.txt.gz
  python3 tools/build_he_bigrams.py -o dicts/he_bigrams.txt --vocab dicts/he.txt \
      he.txt.gz he_*-ud-*.conllu
  # then bump DICT_VERSIONS["he"] in DictModel.kt so installed phones refresh.

Notes on tokenisation: tokens are surface words made only of Type's Hebrew letters (finals
included); nikud/cantillation is stripped and edge punctuation trimmed. Any token that still
contains a non-letter (a number, Latin, an abbreviation like מג"ב) breaks the bigram chain, so no
false adjacency is counted across it.

--vocab restricts both halves of a pair to the shipped unigram dictionary (dicts/he.txt). Use it
for any web-scale corpus: the on-device model can only ever *correct to* or *suggest* a listed
word, so out-of-vocabulary pairs are dead weight — and the OpenSubtitles Hebrew text carries a lot
of space-stripped lines ("אוליתצרףאותנו"), whose glued tokens the dictionary filters out.
"""
import argparse
import collections
import gzip
import re
import sys

# Type's Hebrew alphabet (see Dictionaries.build "he"), including the five final forms.
ALPHA = set("אבגדהוזחטיךכלםמןנסעףפץצקרשת")
NIKUD = re.compile(r"[֑-ׇֽֿׁׂׅׄ]")
EDGE = ".,;:!?()[]{}\"'–—“”‘’«»׳״/\\|<>*=+_…"
SENT_SPLIT = re.compile(r"[.!?…]+|\n")
ID_BASE = 1 << 24          # pair key = prev_id * ID_BASE + next_id (room for 16.7M distinct words)

TEXT_PREFIX = "# text = "


def clean(tok):
    """A surface token stripped to Type's alphabet, or None if it isn't a pure-Hebrew word."""
    tok = NIKUD.sub("", tok).strip(EDGE)
    if not tok:
        return None
    return tok if all(c in ALPHA for c in tok) else None


def open_maybe_gz(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, "r", encoding="utf-8", errors="replace")


def sentences(path):
    """Yield sentences from a corpus file (conllu '# text' lines, or split plain text)."""
    is_conllu = path.endswith(".conllu")
    with open_maybe_gz(path) as fh:
        if is_conllu:
            for line in fh:
                if line.startswith(TEXT_PREFIX):
                    yield line[len(TEXT_PREFIX):].strip()
        else:
            for line in fh:
                for s in SENT_SPLIT.split(line):
                    s = s.strip()
                    if s:
                        yield s


def read_vocab(path):
    """The shipped unigram dictionary as a set — "word<space>count" rows (dicts/he.txt)."""
    vocab = set()
    with open_maybe_gz(path) as fh:
        for line in fh:
            w = line.split(" ", 1)[0].strip()
            if w:
                vocab.add(w)
    return vocab


def build(paths, min_count, cap, vocab=None, max_keys=0):
    """Count bigrams over [paths]; return (kept rows, sentences read, distinct pairs, count floor).

    Pairs are counted as integer keys (prev_id * ID_BASE + next_id) rather than string tuples: the
    87M-line OpenSubtitles corpus yields 16M distinct pairs even after the vocabulary filter, and
    interned ids hold that in ~1.5 GB. [max_keys] bounds it — each time the table outgrows it the
    count floor rises by one and everything at or below it is dropped (lossy counting). The floor is
    reported, and pairs are only lost below it, i.e. among the rare ones the cap would drop anyway.
    """
    bi = collections.Counter()
    ids, words = {}, []
    n_sent = 0
    floor = 0                          # counts <= floor have been pruned away
    for path in paths:
        for sent in sentences(path):
            n_sent += 1
            prev = None
            for raw in sent.split():
                w = clean(raw)
                if w is None or (vocab is not None and w not in vocab):
                    prev = None        # boundary: punctuation / number / mixed / unlisted token
                    continue
                i = ids.get(w)
                if i is None:
                    if len(words) >= ID_BASE:
                        sys.exit("corpus has more distinct words than ID_BASE — pass --vocab")
                    i = ids[w] = len(words)
                    words.append(w)
                if prev is not None:
                    bi[prev * ID_BASE + i] += 1
                prev = i
            if max_keys and len(bi) > max_keys:
                floor += 1
                bi = collections.Counter({k: c for k, c in bi.items() if c > floor})
    kept = [(words[k // ID_BASE], words[k % ID_BASE], c) for k, c in bi.items() if c >= min_count]
    kept.sort(key=lambda t: (-t[2], t[0], t[1]))
    if cap > 0:
        kept = kept[:cap]
    return kept, n_sent, len(bi), floor


def main():
    ap = argparse.ArgumentParser(description="Build dicts/he_bigrams.txt from Hebrew corpora.")
    ap.add_argument("inputs", nargs="+", help="corpus files (.txt/.conllu, optionally .gz)")
    ap.add_argument("-o", "--out", default="dicts/he_bigrams.txt", help="output path")
    ap.add_argument("--min-count", type=int, default=2, help="drop pairs seen fewer times (default 2)")
    ap.add_argument("--cap", type=int, default=400000, help="max pairs to keep, 0 = unlimited")
    ap.add_argument("--vocab", help="unigram dictionary (dicts/he.txt): keep only pairs of listed words")
    ap.add_argument("--max-keys", type=int, default=40_000_000,
                    help="memory bound on the count table; 0 = unlimited (default 40M ≈ 4 GB)")
    args = ap.parse_args()

    vocab = read_vocab(args.vocab) if args.vocab else None
    kept, n_sent, n_all, floor = build(args.inputs, args.min_count, args.cap, vocab, args.max_keys)
    if not kept:
        sys.exit("no bigrams produced — check the input corpus")
    with open(args.out, "w", encoding="utf-8") as out:
        for p, n, c in kept:
            out.write(f"{p} {n} {c}\n")
    if vocab is not None:
        print(f"vocabulary     : {len(vocab):,} words ({args.vocab})")
    print(f"sentences read : {n_sent:,}")
    print(f"distinct pairs : {n_all:,}" + (f" (pruned below {floor + 1})" if floor else ""))
    if floor >= args.min_count:
        print(f"note: the memory bound pruned at {floor}, above --min-count {args.min_count};"
              " raise --max-keys for an exact count at that threshold")
    print(f"written (>= {args.min_count}): {len(kept):,} -> {args.out}")
    print("top:", ", ".join(f"{p} {n} ({c})" for p, n, c in kept[:5]))


if __name__ == "__main__":
    main()
