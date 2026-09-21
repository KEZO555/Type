#!/usr/bin/env python3
"""
Score a Hebrew next-word model (dicts/he_bigrams.txt) on a held-out corpus.

Answers the only question that matters when the model changes: given the word just typed, how
often is the *next* word the one the bar would have offered? Two measures, both over word pairs
the corpus actually contains:

  next-word   — the bar with nothing typed yet (WordDictionary.nextWords): top-1 / top-3 of the
                candidates the model lists after the previous word.
  completion  — the bar after the first two letters (WordDictionary.completions): the same list
                filtered to that prefix, which is how the model re-ranks completions in practice.

Every rate is over *all* eval pairs, so a pair whose previous word the model has never seen counts
as a miss — that is what the user experiences, and it keeps models of different sizes comparable.
(Rates conditional on coverage do the opposite: a model that knows almost nothing scores well on
the little it knows.) "context" reports coverage separately. Use a corpus the model was NOT built
from, or the score is meaningless:

  curl -o eval-ted.txt.gz https://object.pouta.csc.fi/OPUS-TED2020/v1/mono/he.txt.gz
  python3 tools/eval_he_bigrams.py --corpus eval-ted.txt.gz --limit 200000 \
      dicts/he_bigrams.txt other_model.txt        # several models = a side-by-side table
"""
import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_he_bigrams import clean, open_maybe_gz, sentences   # same tokenisation as the builder

PREFIX_LEN = 2          # WordDictionary.completions ignores prefixes shorter than this


def load_model(path):
    """{prev: [(next, count), ...]} ordered by count, as WordPredict.topNext would rank them."""
    m = collections.defaultdict(list)
    with open_maybe_gz(path) as fh:
        for line in fh:
            parts = line.split()
            if len(parts) != 3 or not parts[2].isdigit():
                continue
            m[parts[0]].append((parts[1], int(parts[2])))
    for k in m:
        m[k].sort(key=lambda t: -t[1])
    return m


def pairs(corpus, limit):
    """Adjacent Hebrew word pairs from the eval corpus, tokenised exactly like the builder."""
    n = 0
    for sent in sentences(corpus):
        prev = None
        for raw in sent.split():
            w = clean(raw)
            if w is None:
                prev = None
                continue
            if prev is not None:
                yield prev, w
                n += 1
                if limit and n >= limit:
                    return
            prev = w


def score(model, test):
    s = collections.Counter()
    for prev, nxt in test:
        s["pairs"] += 1
        completable = len(nxt) > PREFIX_LEN
        s["comp_pairs"] += completable
        cands = model.get(prev)
        if not cands:
            continue                   # unknown context: a miss on every measure
        s["context"] += 1
        top = [w for w, _ in cands[:3]]
        s["top1"] += top[:1] == [nxt]
        s["top3"] += nxt in top
        if completable:
            pre = nxt[:PREFIX_LEN]
            comp = [w for w, _ in cands if w.startswith(pre) and len(w) > PREFIX_LEN][:3]
            s["comp1"] += comp[:1] == [nxt]
            s["comp3"] += nxt in comp
    return s


def main():
    ap = argparse.ArgumentParser(description="Score Hebrew next-word models on a held-out corpus.")
    ap.add_argument("models", nargs="+", help="model files in 'prev next count' format")
    ap.add_argument("--corpus", required=True, help="held-out corpus (.txt/.conllu, optionally .gz)")
    ap.add_argument("--limit", type=int, default=200_000, help="word pairs to score, 0 = all")
    args = ap.parse_args()

    test = list(pairs(args.corpus, args.limit))
    if not test:
        sys.exit("no word pairs in the eval corpus")
    print(f"corpus: {args.corpus} — {len(test):,} word pairs\n")
    print(f"{'model':<34}{'pairs':>10}{'context':>9}{'top-1':>8}{'top-3':>8}{'compl-1':>9}{'compl-3':>9}")
    for path in args.models:
        model = load_model(path)
        s = score(model, test)
        n, cn = s["pairs"], s["comp_pairs"] or 1
        name = f"{os.path.basename(path)} ({sum(len(v) for v in model.values()):,})"
        print(f"{name:<34}{n:>10,}{s['context'] / n:>8.1%}"
              f"{s['top1'] / n:>8.1%}{s['top3'] / n:>8.1%}{s['comp1'] / cn:>9.1%}{s['comp3'] / cn:>9.1%}")


if __name__ == "__main__":
    main()
