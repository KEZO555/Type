#!/usr/bin/env python3
"""
Build a BIG dicts/he_bigrams.txt from a large free Hebrew corpus — run this LOCALLY (the corpus hosts
are unreachable from CI). Produces the exact "word1 word2 count" format the keyboard loads, so it's a
drop-in replacement for the small UD-treebank model.

Recommended corpus: OpenSubtitles (Hebrew) from OPUS — hundreds of millions of words of conversational,
everyday Hebrew, the register people actually type on a phone. Other options work too (Hebrew Wikipedia,
OSCAR, CC-100 he): anything that's one-or-more lines of plain Hebrew text. Subtitles/Wikipedia are
already whitespace-tokenised into the GLUED surface forms people type ("בבית", not "ב בית"), so unlike
the UD treebanks we don't need multiword-token handling — we just read words off the line.

Usage:
  # download + build (default corpus = OPUS OpenSubtitles he mono, ~100MB gz):
  python3 tools/gen_bigrams_he_opensubtitles.py

  # use a corpus you already downloaded (.txt or .txt.gz), and fold in the existing UD model:
  python3 tools/gen_bigrams_he_opensubtitles.py --corpus /path/he.txt.gz --merge dicts/he_bigrams.txt

  # tune size/quality:
  python3 tools/gen_bigrams_he_opensubtitles.py --min-count 5 --max-pairs 60000

Memory: counts every in-vocab bigram in a dict — a few GB RAM for OpenSubtitles. If that's too much,
raise --min-count or pre-split the corpus and merge the per-shard outputs with --merge.
"""
import argparse, gzip, io, os, re, sys, urllib.request
from collections import Counter

DEFAULT_URL = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/he.txt.gz"
HE_WORD = re.compile(r"[א-ת]+")          # a run of Hebrew letters (incl. final forms)
SENT_BREAK = re.compile(r"[.?!…:;\"'()\[\]{}<>\n\r\t]|--")  # punctuation that ends a context run

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(here)


def load_vocab(path):
    vocab = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            w = line.split(" ", 1)[0]
            if w:
                vocab.add(w)
    return vocab


def open_text(path):
    """Open a .txt or .txt.gz corpus as a UTF-8 text stream."""
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8", errors="replace")
    return open(path, encoding="utf-8", errors="replace")


def words_with_breaks(line):
    """Yield Hebrew words in order, with None at sentence-ending punctuation, so a bigram never spans
    a clause break (mirrors the sentence boundaries the UD builder respects)."""
    for chunk in SENT_BREAK.split(line):
        for m in HE_WORD.finditer(chunk):
            yield m.group(0)
        yield None  # the split point is a soft boundary


def count_bigrams(corpus_path, vocab):
    big = Counter()
    prev = None
    lines = 0
    with open_text(corpus_path) as f:
        for line in f:
            lines += 1
            if lines % 2_000_000 == 0:
                print(f"  …{lines:,} lines, {len(big):,} distinct pairs", file=sys.stderr)
            prev = None
            for w in words_with_breaks(line):
                if w is None or w not in vocab:
                    prev = None
                    continue
                if prev is not None:
                    big[(prev, w)] += 1
                prev = w
    return big


def read_bigram_file(path):
    out = Counter()
    if not path or not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 3 and parts[2].isdigit():
                out[(parts[0], parts[1])] += int(parts[2])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", help="local corpus .txt/.txt.gz (else download --url)")
    ap.add_argument("--url", default=DEFAULT_URL, help="corpus URL to download if --corpus is absent")
    ap.add_argument("--vocab", default=os.path.join(repo, "dicts/he.txt"))
    ap.add_argument("--merge", help="another bigram file whose counts to add in (e.g. the UD model)")
    ap.add_argument("--min-count", type=int, default=4)
    ap.add_argument("--max-pairs", type=int, default=60000)
    ap.add_argument("--out", default=os.path.join(repo, "dicts/he_bigrams.txt"))
    args = ap.parse_args()

    corpus = args.corpus
    if not corpus:
        corpus = "/tmp/he_opensubtitles.txt.gz"
        if not os.path.exists(corpus):
            print(f"downloading {args.url} → {corpus}", file=sys.stderr)
            urllib.request.urlretrieve(args.url, corpus)
    vocab = load_vocab(args.vocab)
    print(f"vocab: {len(vocab):,} words; counting bigrams in {corpus} …", file=sys.stderr)

    big = count_bigrams(corpus, vocab)
    if args.merge:
        merged = read_bigram_file(args.merge)
        print(f"merging {len(merged):,} pairs from {args.merge}", file=sys.stderr)
        big.update(merged)

    kept = [(a, b, c) for (a, b), c in big.items() if c >= args.min_count]
    kept.sort(key=lambda x: -x[2])
    kept = kept[: args.max_pairs]

    out_dir = os.path.dirname(args.out)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fo:
        for a, b, c in kept:
            fo.write(f"{a} {b} {c}\n")
    prevs = len({a for a, _, _ in kept})
    print(f"wrote {args.out}  ({len(kept):,} pairs over {prevs:,} context words, "
          f"{os.path.getsize(args.out):,} bytes)")


if __name__ == "__main__":
    main()
