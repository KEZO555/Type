#!/usr/bin/env python3
"""
Build a downloadable autocorrect dictionary for a Latin-script language: "word<space>count" per line,
lowercase, language-alphabet only, top MAX_WORDS by frequency. Mirrors the bundled en_words.txt format
so the on-device loader is identical.

Source: a hermitdave/FrequencyWords "word<space>count" list (already frequency-sorted), e.g.
  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_50k.txt

Usage:  python3 gen_lang.py <code> <source.txt> <out.txt>
"""
import re, sys, os

MAX_WORDS = 40000
MIN_LEN, MAX_LEN = 1, 20

# Allowed letters per language (lowercase). Words must match ^[these]+$ .
ALPHABETS = {
    "es": "abcdefghijklmnopqrstuvwxyzñáéíóúü",
    "fr": "abcdefghijklmnopqrstuvwxyzàâçéèêëîïôûùüÿœæ",
    "de": "abcdefghijklmnopqrstuvwxyzäöüß",
    "it": "abcdefghijklmnopqrstuvwxyzàèéìíòóùú",
    "pt": "abcdefghijklmnopqrstuvwxyzãõáàâçéêíóôúü",
    # Arabic: 28 base letters + ة ى and the hamza forms (matches LangDef.AR.autocorrectAlphabet).
    "ar": "ابتثجحخدذرزسشصضطظعغفقكلمنهويةىءأإآؤئ",
    # Mandarin pinyin (romanized) — lowercase a–z plus ü. Build the source list with tools/gen_pinyin.py.
    "zh": "abcdefghijklmnopqrstuvwxyzü",
}

def main():
    code, src, out = sys.argv[1], sys.argv[2], sys.argv[3]
    alpha = ALPHABETS[code]
    word_re = re.compile(r"^[" + alpha + r"]+$")
    kept = []
    seen = set()
    with open(src, encoding="utf-8", errors="ignore") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2:
                continue
            w, c = parts[0].lower(), parts[1]
            if w in seen or not word_re.match(w) or not (MIN_LEN <= len(w) <= MAX_LEN):
                continue
            try:
                count = int(c)
            except ValueError:
                continue
            seen.add(w)
            kept.append((w, count))
            if len(kept) >= MAX_WORDS:
                break  # source is already descending-frequency
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        for w, c in kept:
            f.write(f"{w} {c}\n")
    print(f"{code}: wrote {len(kept)} words -> {out}")

if __name__ == "__main__":
    main()
