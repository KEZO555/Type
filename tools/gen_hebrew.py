#!/usr/bin/env python3
"""
Build the two Hebrew assets the keyboard needs, from a "word<space>frequency" list
plus one or more real Hebrew lexicons used to scrub junk out of the long tail.

  1. dicts/he.txt
     The downloadable Hebrew dictionary for autocorrect — "word freq" per line, plain text.
     Loaded by WordDictionary (Norvig-style edit-distance corrector). Stored uncompressed
     in the repo; the APK's own deflate shrinks it on device. (Don't gzip it — AGP
     auto-decompresses .gz assets at packaging time, which just renames the entry.)

  2. app/src/main/res/raw/hebcharmodel.bin
     The Hebrew analogue of charmodel.bin — a character trigram model for the
     per-tap typing-accuracy layer (little-endian float32 [N][N][N], ln P(c3|c1,c2)),
     over the Hebrew alphabet. Built from the same cleaned word list.

Why a lexicon?
  The raw frequency list (OpenSubtitles) is full of proper nouns, foreign-name
  transliterations (מייקל, פרנק), interjections and OCR fragments. Left in, the
  corrector happily "fixes" typos onto them. We therefore admit a corpus word only if:
      • it is among the HEAD_KEEP most frequent words (so genuinely common vocabulary —
        including colloquial spellings the lexicon may lack, e.g. אמא/אוקיי — is never
        dropped), OR
      • it (or its Hebrew proclitic stem, matching the keyboard's own ו/ה/ש/ב/כ/ל/מ
        splitting) appears in a real Hebrew lexicon.
  Everything else — the rare, unlexiconned tail where the junk lives — is discarded.
  The kept words, in frequency order, are capped at MAX_WORDS.

Sources (download once, then pass as args):
  frequency : hermitdave/FrequencyWords content/2018/he/he_full.txt
  lexicons  : LibreOffice/dictionaries he_IL/he_IL.dic  (Hspell-derived, ~469k forms)
              wooorm/dictionaries dictionaries/he/index.dic  (optional, near-subset)
  Each lexicon is a hunspell .dic: a leading count line, then "surfaceform/FLAGS" lines.

Usage:  python3 gen_hebrew.py [he_full.txt] [lexicon.dic ...]
        (defaults: /tmp/he_full.txt and /tmp/lo_he.dic, /tmp/woo_he.dic if present)
"""
import struct, math, re, sys, os

HE_BASE = 0x05D0          # alef
HE_SIZE = 27              # alef..tav inclusive (finals included)
N = HE_SIZE + 1           # + word boundary
BND = HE_SIZE             # boundary symbol index (27)

ADD_K = 0.05
L3, L2, L1 = 0.7, 0.25, 0.05   # interpolation weights: trigram, bigram, unigram

MIN_LEN, MAX_LEN = 2, 15
HEAD_KEEP = 8000               # most-frequent words admitted unconditionally
MAX_WORDS = 70000              # cap on the bundled dictionary size (Hebrew keeps all of it on device)

HE_WORD = re.compile(r"^[א-ת]+$")   # Hebrew letters only, nothing else
PROCLITICS = set("משהוכלב")          # the gluable one-letter prefixes (matches TextOps)

here = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(here)
src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/he_full.txt"
lex_args = sys.argv[2:]
if not lex_args:
    lex_args = [p for p in ("/tmp/lo_he.dic", "/tmp/woo_he.dic") if os.path.exists(p)]
dict_out = os.path.join(repo, "dicts/he.txt")
model_out = os.path.join(repo, "app/src/main/res/raw/hebcharmodel.bin")


def idx(ch):
    return ord(ch) - HE_BASE


def proclitic_stems(w, max_strip=3, min_stem=2):
    """The stems under up to 3 glued proclitics — mirrors TextOps.hebrewProcliticSplits."""
    out, i = [], 0
    while i < max_strip and i < len(w) and w[i] in PROCLITICS and len(w) - (i + 1) >= min_stem:
        i += 1
        out.append(w[i:])
    return out


# ---- load the real-word lexicon(s) -------------------------------------------
if not lex_args:
    sys.exit("no lexicon given; see the docstring for the source .dic files")
lex = set()
for path in lex_args:
    with open(path, encoding="utf-8", errors="ignore") as f:
        next(f, None)   # leading count line
        for line in f:
            word = line.split("/", 1)[0].strip()
            if word:
                lex.add(word)
print(f"lexicon surface forms: {len(lex):,}  (from {', '.join(os.path.basename(p) for p in lex_args)})")


def is_real(w):
    return w in lex or any(s in lex for s in proclitic_stems(w))


# ---- read + frequency-sort the candidate words -------------------------------
rows = []
with open(src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts
        if not HE_WORD.match(w) or not (MIN_LEN <= len(w) <= MAX_LEN):
            continue
        try:
            rows.append((w, int(float(c))))
        except ValueError:
            continue
rows.sort(key=lambda x: -x[1])
print(f"candidate Hebrew words: {len(rows):,}")

# ---- admit head-or-lexicon words, in frequency order, up to the cap ----------
floor = rows[HEAD_KEEP][1] if len(rows) > HEAD_KEEP else 0
words = []
for w, c in rows:
    if c >= floor or is_real(w):
        words.append((w, c))
        if len(words) >= MAX_WORDS:
            break
print(f"dictionary words kept: {len(words):,}  (head floor freq = {floor})")

# ---- 1. bundled dictionary (plain-text "word freq") --------------------------
os.makedirs(os.path.dirname(dict_out), exist_ok=True)
with open(dict_out, "w", encoding="utf-8") as fo:
    for w, c in words:
        fo.write(f"{w} {c}\n")
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

print("sanity (higher = more likely):")
print(f"  ln P(ל | ש, ל) = {lp('ש','ל','ל'):.2f}")
print(f"  ln P(ה | ז, ה) = {lp('ז','ה','ה'):.2f}")
