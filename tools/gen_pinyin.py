#!/usr/bin/env python3
"""
Build a Mandarin **pinyin** frequency list for the QWERTY-pinyin keyboard: "pinyin<space>count" per line,
lowercase romanized pinyin (no tone marks, e.g. 中国 -> zhongguo), top MAX_WORDS by summed frequency.
Same "word<space>count" format the on-device loader expects.

Chinese frequency lists are in Hanzi, so we convert each word to pinyin (pypinyin) and aggregate counts
for identical romanizations. Multiple Chinese words can map to the same pinyin; their counts add up.

Source: a hermitdave/FrequencyWords Hanzi "word<space>count" list, e.g.
  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/zh_cn/zh_cn_50k.txt

Usage:  pip install pypinyin
        python3 gen_pinyin.py <zh_source.txt> <out.txt>
"""
import re, sys, collections
from pypinyin import lazy_pinyin, Style

MAX_WORDS = 40000
MIN_LEN, MAX_LEN = 1, 20
PINYIN_RE = re.compile(r"^[a-zü]+$")   # romanized pinyin keeps ü (e.g. nü, lü); tones are dropped


def main():
    src, out = sys.argv[1], sys.argv[2]
    counts = collections.defaultdict(int)
    with open(src, encoding="utf-8", errors="ignore") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2:
                continue
            hanzi, c = parts
            try:
                c = int(c)
            except ValueError:
                continue
            word = "".join(lazy_pinyin(hanzi, style=Style.NORMAL, errors="ignore")).lower()
            if not (MIN_LEN <= len(word) <= MAX_LEN) or not PINYIN_RE.match(word):
                continue
            counts[word] += c
    top = sorted(counts.items(), key=lambda kv: -kv[1])[:MAX_WORDS]
    with open(out, "w", encoding="utf-8") as f:
        for w, c in top:
            f.write(f"{w} {c}\n")
    print(f"zh: {len(counts)} distinct pinyin -> wrote {len(top)} -> {out}")


if __name__ == "__main__":
    main()
