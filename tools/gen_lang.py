#!/usr/bin/env python3
"""
Build a downloadable autocorrect dictionary for a Latin-script language: "word<space>count" per line,
lowercase, language-alphabet only, top MAX_WORDS by frequency. Mirrors the bundled en_words.txt format
so the on-device loader is identical.

Source: a hermitdave/FrequencyWords "word<space>count" list (already frequency-sorted), e.g.
  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_50k.txt

Usage:  python3 gen_lang.py <code> <source.txt> <out.txt>
"""
import io, re, sys, os

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
    "nl": "abcdefghijklmnopqrstuvwxyzëïöüéèêáàçóú",   # shared by both Dutch layouts (QWERTY + AZERTY)
    "ru": "абвгдеёжзийклмнопрстуфхцчшщъыьэюя",          # Russian (Cyrillic)
    "pl": "abcdefghijklmnopqrstuvwxyząćęłńóśźż",
    # Nordic — each is the layout's letters plus its 123-key long-press accents (matches LangDef).
    "no": "abcdefghijklmnopqrstuvwxyzåøæéèêóòôàäöü",    # Norwegian (bokmål-leaning subtitle corpus)
    "da": "abcdefghijklmnopqrstuvwxyzåæøéäöü",          # Danish
    "sv": "abcdefghijklmnopqrstuvwxyzåöäéàüæø",          # Swedish
    "fi": "abcdefghijklmnopqrstuvwxyzåöäšžéüæø",         # Finnish
    "is": "abcdefghijklmnopqrstuvwxyzðæöþáéíóúý",        # Icelandic
}

def _latin4_as_latin1(w):
    """Undo a mis-decoding: text that was Latin-1 but read as ISO-8859-4, so ð→đ, þ→ū, ó→ķ, ý→ũ, ò→ō.
    Words already holding real ð/þ can't be Latin-4-encoded and are returned unchanged."""
    try:
        return w.encode("iso8859_4").decode("latin1")
    except UnicodeEncodeError:
        return w

# Per-language fix-ups for a damaged source. The FrequencyWords Icelandic list (content/2018/is) mixes
# correctly encoded subtitles with Latin-1 ones that were read as Latin-4: "ađ" for "að", "ūú" for
# "þú". Repairing and merging the two spellings recovers the real counts.
REPAIRS = {
    "is": _latin4_as_latin1,
}

def _read_repaired(src, repair):
    """Read the whole source, repair each word, merge counts of words that now coincide, and return
    (word, count) pairs in descending frequency — the order main() expects."""
    merged = {}
    with open(src, encoding="utf-8", errors="ignore") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2:
                continue
            try:
                count = int(parts[1])
            except ValueError:
                continue
            w = repair(parts[0].lower())
            merged[w] = merged.get(w, 0) + count
    return sorted(merged.items(), key=lambda kv: (-kv[1], kv[0]))

def main():
    code, src, out = sys.argv[1], sys.argv[2], sys.argv[3]
    alpha = ALPHABETS[code]
    word_re = re.compile(r"^[" + alpha + r"]+$")
    kept = []
    seen = set()
    if code in REPAIRS:
        f = io.StringIO("".join(f"{w} {c}\n" for w, c in _read_repaired(src, REPAIRS[code])))
    else:
        f = open(src, encoding="utf-8", errors="ignore")
    with f:
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
