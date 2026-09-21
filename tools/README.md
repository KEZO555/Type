# Keyboard tooling

## `gen_charmodel.py` — typing-accuracy language model

Generates `app/src/main/res/raw/charmodel.bin`, the character trigram model the keyboard uses for
per-tap accuracy (spatial × language key selection in `LightKeyboardView`).

It learns `P(next_letter | prev2, prev1)` over a 27-symbol alphabet (a-z + a word-boundary symbol),
frequency-weighted from a real word list, with trigram/bigram/unigram interpolation for smoothing.
Output is a little-endian float32 table, flattened `index = (c1*27 + c2)*27 + c3`, value `ln P`.

### Regenerate

```sh
curl -s -o /tmp/count_1w.txt http://norvig.com/ngrams/count_1w.txt   # ~4.7 MB, 333k words+counts
python3 gen_charmodel.py /tmp/count_1w.txt \
    ../app/src/main/res/raw/charmodel.bin
```

Source word list: Peter Norvig's `count_1w.txt` (Google Web Trillion Word Corpus unigrams).
Tunables for accuracy (Gaussian width, context weight `lambda`, touch offset `biasX/biasY`) live in
`LightKeyboardView.kt`, not here.

## `build_he_bigrams.py` / `eval_he_bigrams.py` — the Hebrew next-word model

`dicts/he_bigrams.txt` is the pre-trained `prev next count` model phones download with the Hebrew
dictionary. It drives the suggestion bar's next-word predictions, re-ranks completions, and gives
`contextCorrect` the evidence to fix a *valid* word that's wrong in context (תודה כבה → תודה רבה).

### Regenerate

```sh
curl -o /tmp/he.txt.gz https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/he.txt.gz  # ~950 MB
for tb in HTB:he_htb IAHLTwiki:he_iahltwiki IAHLTknesset:he_iahltknesset; do
  for s in train dev test; do
    curl -o "/tmp/${tb#*:}-ud-$s.conllu" \
      "https://raw.githubusercontent.com/UniversalDependencies/UD_Hebrew-${tb%%:*}/master/${tb#*:}-ud-$s.conllu"
  done
done
python3 tools/build_he_bigrams.py -o dicts/he_bigrams.txt --vocab dicts/he.txt \
    /tmp/he.txt.gz /tmp/he_*-ud-*.conllu          # ~15 min, ~3 GB RAM
```

Then **bump `DICT_VERSIONS["he"]` in `DictModel.kt`** — that's what makes phones that already hold
an older copy re-download it.

The corpus yields ~10M pairs seen twice or more; `--cap` keeps the commonest, and the default 400k
is what ships (8.3 MB, every pair seen at least 60 times). The cap is the one real knob here: it
trades download size and IME heap — both roughly linear in it — against how often the bar has
anything useful to say. Re-score before moving it.

OpenSubtitles supplies the conversational register people actually type in; the treebanks add
newswire, encyclopedic and parliamentary breadth with gold word-segmentation. `--vocab` keeps only
pairs of words listed in `dicts/he.txt`, which is what makes a web-scale corpus usable: the
keyboard can only ever suggest or correct to a listed word, and the Hebrew OpenSubtitles text is
littered with space-stripped lines (`אוליתצרףאותנו`) the dictionary filters out.

### Check before shipping

Score any candidate against the shipped model on a corpus it was *not* built from:

```sh
curl -o /tmp/eval-ted.txt.gz https://object.pouta.csc.fi/OPUS-TED2020/v1/mono/he.txt.gz
python3 tools/eval_he_bigrams.py --corpus /tmp/eval-ted.txt.gz dicts/he_bigrams.txt old_model.txt
```

`context` is how often the model knows the word just typed at all; `top-1`/`top-3` are how often
the bar's next-word picks are right and `compl-1`/`compl-3` how often its picks are right once two
letters are typed. The rates are over every pair in the corpus — an unknown context counts as a
miss — so models of different sizes compare directly.
