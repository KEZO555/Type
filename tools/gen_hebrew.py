#!/usr/bin/env python3
"""
Build the two Hebrew assets the keyboard needs, from a "word<space>frequency" list
(e.g. hermitdave/FrequencyWords content/2018/he/he_50k.txt):

  1. dicts/he.txt
     The downloadable Hebrew dictionary for autocorrect — "word freq" per line, plain text.
     Filtered to words made *only* of the 27 Hebrew letter forms, length 2..15.
     Loaded by HebrewDictionary (Norvig-style edit-distance corrector). Stored uncompressed
     in the repo; the APK's own deflate shrinks it to ~210 KB on device. (Don't gzip it —
     AGP auto-decompresses .gz assets at packaging time, which just renames the entry.)

  2. app/src/main/res/raw/hebcharmodel.bin
     The Hebrew analogue of charmodel.bin — a character trigram model for the
     per-tap typing-accuracy layer. Same on-disk format as the English one
     (little-endian float32 [N][N][N], value = ln P(c3 | c1, c2)), but over the
     Hebrew alphabet instead of a-z.

Alphabet: the 27 contiguous Hebrew letter forms U+05D0..U+05EA (alef..tav,
finals included), indexed by (codepoint - 0x05D0) → 0..26. Index 27 is the
word-boundary symbol, so N = 28. This is exactly the indexing the Kotlin side
uses (Lang.HE: base = 'א', size = 27), so the two stay in lockstep.

Usage:  python3 gen_hebrew.py [he_50k.txt]
"""
import struct, math, re, sys, os

HE_BASE = 0x05D0          # alef
HE_SIZE = 27              # alef..tav inclusive (finals included)
N = HE_SIZE + 1           # + word boundary
BND = HE_SIZE             # boundary symbol index (27)

ADD_K = 0.05
L3, L2, L1 = 0.7, 0.25, 0.05   # interpolation weights: trigram, bigram, unigram

MIN_LEN, MAX_LEN = 2, 15
MAX_WORDS = 40000              # cap the bundled dictionary size

HE_WORD = re.compile(r"^[א-ת]+$")   # Hebrew letters only, nothing else

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(here)
src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/he_50k.txt"
dict_out = os.path.join(repo, "dicts/he.txt")
model_out = os.path.join(repo, "app/src/main/res/raw/hebcharmodel.bin")


def idx(ch):
    return ord(ch) - HE_BASE


# ---- read + filter the frequency list ----------------------------------------
words = []   # (word, weight), kept in frequency order
with open(src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0], parts[1]
        if not HE_WORD.match(w) or not (MIN_LEN <= len(w) <= MAX_LEN):
            continue
        try:
            weight = float(c)
        except ValueError:
            continue
        words.append((w, weight))

words = words[:MAX_WORDS]
print(f"dictionary words kept: {len(words):,}")

# ---- 1. bundled dictionary (plain-text "word freq") --------------------------
os.makedirs(os.path.dirname(dict_out), exist_ok=True)
with open(dict_out, "w", encoding="utf-8") as fo:
    for w, weight in words:
        fo.write(f"{w} {int(weight)}\n")
print(f"wrote {dict_out}  ({os.path.getsize(dict_out):,} bytes)")

# ---- 2. character trigram model ----------------------------------------------
tri = [[[0.0] * N for _ in range(N)] for _ in range(N)]
bi = [[0.0] * N for _ in range(N)]
uni = [0.0] * N
for w, weight in words:
    seq = [BND, BND] + [idx(ch) for ch in w]
    for i in range(2, len(seq)):
        c1, c2, c3 = seq[i - 2], seq[i - 1], seq[i]
        tri[c1][c2][c3] += weight
        bi[c2][c3] += weight
        uni[c3] += weight

uni_tot = sum(uni[c] for c in range(HE_SIZE)) + ADD_K * HE_SIZE
p_uni = [(uni[c] + ADD_K) / uni_tot for c in range(HE_SIZE)]

table = [-30.0] * (N * N * N)   # default: effectively impossible (c3==BND, dead contexts)
for c1 in range(N):
    for c2 in range(N):
        bi_tot = sum(bi[c2][c] for c in range(HE_SIZE)) + ADD_K * HE_SIZE
        tri_tot = sum(tri[c1][c2][c] for c in range(HE_SIZE)) + ADD_K * HE_SIZE
        for c3 in range(HE_SIZE):
            p_tri = (tri[c1][c2][c3] + ADD_K) / tri_tot
            p_bi = (bi[c2][c3] + ADD_K) / bi_tot
            p = L3 * p_tri + L2 * p_bi + L1 * p_uni[c3]
            table[(c1 * N + c2) * N + c3] = math.log(p)

os.makedirs(os.path.dirname(model_out), exist_ok=True)
with open(model_out, "wb") as fo:
    fo.write(struct.pack("<%df" % len(table), *table))
print(f"wrote {model_out}  (entries {len(table):,}, {len(table) * 4:,} bytes)")


# ---- sanity ------------------------------------------------------------------
def lp(a, b, c):
    return table[(idx(a) * N + idx(b)) * N + idx(c)]

# After שׁ-less "של" stem etc. — just show that frequent continuations score higher.
print("sanity (higher = more likely):")
print(f"  P(ל|ש,ל-ctx של)  P(ת|את)  showing a few:")
print(f"  ln P(ל | ש, ל) = {lp('ש','ל','ל'):.2f}")
print(f"  ln P(ה | ז, ה) = {lp('ז','ה','ה'):.2f}")
