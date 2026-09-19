#!/usr/bin/env python3
"""
Build Type's Hebrew next-word (bigram) model — dicts/he_bigrams.txt.

The keyboard's context features (contextCorrect: "picks the wrong valid word", the suggestion
bar, and completion re-ranking) are driven by a "prev next count" bigram list. This tool builds
that list from one or more Hebrew corpora and writes it in the exact on-device format.

Register matters: the model should match how people actually type. For a phone keyboard, a
*conversational* corpus (OpenSubtitles) beats formal text (news / legal / Wikipedia), which is
what the current small treebank-derived model over-represents.

Recommended corpora (need broadened network egress — the default web policy blocks these):
  • OpenSubtitles (conversational, best fit):
      https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/he.txt.gz
  • Hebrew Wikipedia (formal, for breadth) — via a dump + wikiextractor, or the API.
  • UD Hebrew treebanks (modern, gold word-segmentation — reachable even on the default policy):
      https://raw.githubusercontent.com/UniversalDependencies/UD_Hebrew-HTB/master/he_htb-ud-{train,dev,test}.conllu
      https://raw.githubusercontent.com/UniversalDependencies/UD_Hebrew-IAHLTwiki/master/he_iahltwiki-ud-{train,dev,test}.conllu
      https://raw.githubusercontent.com/UniversalDependencies/UD_Hebrew-IAHLTknesset/master/he_iahltknesset-ud-{train,dev,test}.conllu

Inputs may be plain text (one line per sentence/paragraph), .conllu (uses the "# text = " lines,
which carry the true surface forms a user types — proclitics glued on), or gzip (.gz) of either.

Usage:
  python3 tools/build_he_bigrams.py -o dicts/he_bigrams.txt \
      opensubtitles-he.txt.gz he_*-ud-*.conllu
  # then bump DICT_VERSIONS["he"] in DictModel.kt so installed phones refresh.

Notes on tokenisation: tokens are surface words made only of Type's Hebrew letters (finals
included); nikud/cantillation is stripped and edge punctuation trimmed. Any token that still
contains a non-letter (a number, Latin, an abbreviation like מג"ב) breaks the bigram chain, so no
false adjacency is counted across it.
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


def build(paths, min_count, cap):
    bi = collections.Counter()
    n_sent = 0
    for path in paths:
        for sent in sentences(path):
            n_sent += 1
            prev = None
            for raw in sent.split():
                w = clean(raw)
                if w is None:          # boundary: punctuation / number / mixed token
                    prev = None
                    continue
                if prev is not None:
                    bi[(prev, w)] += 1
                prev = w
    kept = [(p, n, c) for (p, n), c in bi.items() if c >= min_count]
    kept.sort(key=lambda t: (-t[2], t[0], t[1]))
    if cap > 0:
        kept = kept[:cap]
    return kept, n_sent, len(bi)


def main():
    ap = argparse.ArgumentParser(description="Build dicts/he_bigrams.txt from Hebrew corpora.")
    ap.add_argument("inputs", nargs="+", help="corpus files (.txt/.conllu, optionally .gz)")
    ap.add_argument("-o", "--out", default="dicts/he_bigrams.txt", help="output path")
    ap.add_argument("--min-count", type=int, default=2, help="drop pairs seen fewer times (default 2)")
    ap.add_argument("--cap", type=int, default=120000, help="max pairs to keep, 0 = unlimited")
    args = ap.parse_args()

    kept, n_sent, n_all = build(args.inputs, args.min_count, args.cap)
    if not kept:
        sys.exit("no bigrams produced — check the input corpus")
    with open(args.out, "w", encoding="utf-8") as out:
        for p, n, c in kept:
            out.write(f"{p} {n} {c}\n")
    print(f"sentences read : {n_sent:,}")
    print(f"distinct pairs : {n_all:,}")
    print(f"written (>= {args.min_count}): {len(kept):,} -> {args.out}")
    print("top:", ", ".join(f"{p} {n} ({c})" for p, n, c in kept[:5]))


if __name__ == "__main__":
    main()
