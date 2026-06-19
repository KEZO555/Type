<img src="assets/images/example.png" alt="Type — a black-and-white keyboard for the Light Phone">

**Type** — a black-and-white keyboard for the Light Phone.
A faithful recreation of the Light Phone 3 keyboard, as a system keyboard for any app.

> Built on [**light-keyboard**](https://github.com/adam-weber/light-keyboard) by
> [**Adam Weber**](https://github.com/adam-weber), extended with more languages, voice dictation, and more.

**Languages (13):** English, Hebrew, Spanish, French, German, Italian, Portuguese, Arabic, Mandarin (QWERTY pinyin), Dutch (QWERTY + Belgian AZERTY), Russian, and Polish — each with its own layout and long-press accents. Only English is built in; every other language downloads its dictionary automatically when you enable it, then works fully offline.

**Typing:** offline autocorrect (keyboard-aware — it weighs where your finger actually landed — with a distance-2 fallback, and uses the previous word as context) and learns your vocabulary in every language · a suggestion bar with next-word prediction · auto-capitalize · double-space for period · a numeric layout that opens automatically for number / phone fields · Hebrew final-letter forms.

**Voice dictation (optional, on-device):** English, Spanish, French, German, Italian, Portuguese, Dutch, Russian, Polish via downloadable Vosk models; Hebrew via the phone's recognizer.

**Make it yours:** emoji picker, haptics, key-press sound, long-press delay, cursor-swipe sensitivity, keyboard height, number row — plus a quick-settings panel on the globe long-press.

**Modular — only English is built in.** To keep the base app small, only English ships its autocorrect
dictionary inside the APK. Every other language **downloads** its dictionary (~0.4–0.6 MB) automatically
the moment you turn it on in **Settings → Languages**; after that it works fully offline and learns your
words, the same as English. Each then gets the full text stack: autocorrect, the suggestion bar,
learned words and next-word prediction.

- **English** — QWERTY, built-in offline autocorrect, learns your words.
- **Hebrew** — Standard Israeli layout (finals included); downloads offline autocorrect that understands
  glued proclitics (ו/ה/ש/ב/כ/ל/מ — so "ושלום" is recognised and "ושל…" completes to "ושלום"); voice via
  the phone's own service (`he-IL`).
- **Arabic** — standard Arabic layout (all 28 letters, hamza forms and harakat on the 123-key
  long-press); downloads offline autocorrect. No voice.
- **Mandarin** — QWERTY **pinyin** (type romanized pinyin; `ü` and its tones on the 123-key long-press);
  downloads offline pinyin autocorrect + completion. It does **not** convert pinyin to Chinese
  characters (that would need a candidate-selection IME). No voice.
- **Spanish / French / German / Italian / Portuguese** — full typing with their layouts (QWERTY, AZERTY,
  QWERTZ) and accents on the 123-key long-press; download offline autocorrect, then learn your words.
  Each also has its own optional **offline voice model** (~30–48 MB) on that screen — fully on-device,
  the same as English.
- **Dutch** — two layouts to choose from: **QWERTY** (Netherlands) and **AZERTY** (Belgium/Flanders),
  both with Dutch accents on the 123-key long-press; downloads offline autocorrect and an offline voice model.
- **Russian** — standard ЙЦУКЕН (Cyrillic) layout, with `ё` and `ъ` on the 123-key long-press; downloads
  offline autocorrect and an offline voice model.
- **Polish** — QWERTY with the Polish diacritics (ą ć ę ł ń ó ś ź ż) on the 123-key long-press; downloads
  offline autocorrect and an offline voice model.

## Install

### With [Obtainium](https://github.com/ImranR98/Obtainium), which keeps you up to date

1. Install Obtainium.
2. Add an app, and give it this repository:
   `https://github.com/KEZO555/light-keyboard`
3. It installs the latest release, and tells you when there is a new one.

### Or by hand

Download the latest APK from [Releases](../../releases) and open it.

## Turn it on

Open Type. The setup screen holds everything:

1. **Enable Type**: opens Android's keyboard settings, where you switch it on.
2. **Choose it as your keyboard**: makes it the active one.

The settings below it:

- **Languages**: choose which languages the globe key (bottom row) cycles through; the emoji key opens
  emoji. English, Hebrew, Spanish, French, German, Italian, Portuguese, Arabic, Mandarin pinyin, Dutch
  (QWERTY + Belgian AZERTY), Russian and Polish. Only English's autocorrect dictionary is built in —
  every other language **downloads** its dictionary automatically when you turn it on here, then works
  fully offline. Hebrew and Arabic are caseless, so they have no shift key.
- **Autocorrect** (on by default): fixes misspellings as you type, using the active language's frequency
  dictionary (English built in, the rest downloaded) which also learns the words you use. Keyboard-aware
  (it prefers a neighbouring-key slip or a swapped pair), with a conservative distance-2 fallback for
  longer words, so it doesn't replace words you meant. Fully offline. Turn it off to type exactly what
  you tap. **It learns your vocabulary** in every language: an unfamiliar word with no near match is kept
  the first time; one that looks like a typo is offered as a correction once, but the second time you
  type it the keyboard trusts it — adds it to your vocabulary and stops correcting it. Rejecting a
  correction (backspace) also teaches it your word.
- **Suggestion bar** (on by default): word completions as you type, and — right after a space —
  **next-word prediction** from the word pairs it learns from your own typing (so "happy" might suggest
  "birthday"). Tap a suggestion to insert it; when autocorrect would change the word the bar leads with
  the fix alongside your literal word (tap to keep it). Fully offline; the next-word model is
  per-language and never leaves your phone.
- **Auto-capitalize** (on by default): capitalize the first letter of each sentence.
- **Double-space for period** (on by default): tap space twice to insert `. `.
- **Haptic feedback**: cycle Off / Light / Medium / Strong; tapping previews the strength.
- **Key-press sound** (off by default): the system click on each key, at your device's sound-effect volume.
- **Long-press delay**: Slow / Normal / Fast — how long to hold a key before its symbol/accents appear.
- **Cursor swipe**: Low / Normal / High — how far you slide on the space bar to move the caret one step.
- **Keyboard height**: Compact / Normal / Tall.
- **Number row** (off by default): a persistent row of digits above the letters.
- **Emoji**: tap a grid of candidate emoji to choose which ones appear in the keyboard's emoji panel
  (recently-used still float to the front).
- **Voice dictation** (off by default): turn it on, then download a language's offline model from
  **Settings → Languages**. A mic appears on the period key — long-press it (or tap the mic) to speak
  instead of type, fully on-device. **English, Spanish, French, German, Italian, Portuguese, Dutch,
  Russian and Polish** have offline voice models (~30–50 MB each). **Hebrew** has no offline model, so it
  falls back to the phone's own voice service (which a de-Googled Light Phone may not have). **Arabic and
  Mandarin** have no voice. Tap a downloaded model again to delete it and reclaim the space.

That is the whole setup.

## Gestures

- **Globe** (marked with a small ⚙) — tap cycles your enabled languages (the active one is shown on the
  space bar, e.g. `EN` / `NL` / `RU`); **long-press opens a quick-settings panel** right on the keyboard
  (haptic strength, autocorrect, auto-capitalize, double-space, number row, plus a shortcut to the full
  settings) — no need to leave the app you're typing in. **Comma key** — tap types a comma; **long-press
  opens the emoji panel** (recently-used float to the front).
- **Long-press a letter** — pops up its corner number/symbol (selected by default); release to type it,
  or slide off the key to cancel and keep the letter.
- **Long-press the `123`/`ABC` key** (hinted with the first one in its corner) — a picker of the active
  language's accents: accented letters (Latin), vowel points (Hebrew), hamza forms and harakat (Arabic),
  or pinyin tone marks (Mandarin).
- **Long-press the period** — starts voice dictation, in any language whose offline voice model you've
  downloaded (Hebrew has no offline model and needs a system recognizer most Light Phones don't have).
- **Double-tap space** — inserts `. ` (sentence end).
- **Drag the space bar** — moves the cursor like an iPhone trackpad: left/right by character, up/down
  by line.
- **Hold backspace** — repeats, then deletes whole words after a longer hold.
- **Enter** — inserts a newline (or submits in Go/Search/Send/Next fields); it no longer closes the
  keyboard. **Long-press Enter** — hides the keyboard.
- **Swipe down** — hides the keyboard.

Haptics are light and crisp (short, low-amplitude), tuned to feel close to the iPhone keyboard, and
respect the device's haptic-feedback setting.

Hebrew also snaps a letter to its **final form** at the end of a word automatically (e.g. typing מ as
the last letter becomes ם), and back when the word continues.

## Build it yourself

```sh
./gradlew :app:assembleDebug      # debug build
./gradlew :app:assembleRelease    # release build (unsigned unless signing env vars are set)
```

Needs JDK 17 and the Android SDK (API 35). Tagged releases (`v*`) are built and signed by
[`.github/workflows/release.yml`](.github/workflows/release.yml).

Only English's dictionary (`app/src/main/assets/en_words.txt`, built by
[`tools/gen_english.py`](tools/gen_english.py) from a frequency list such as
[Norvig's count_1w.txt](https://norvig.com/ngrams/count_1w.txt)) is bundled. Every other language's
dictionary lives in [`dicts/`](dicts) and is downloaded on demand: Hebrew (`dicts/he.txt`, from
[`tools/gen_hebrew.py`](tools/gen_hebrew.py)), Arabic (`dicts/ar.txt`) and the Latin languages (on the
`dicts-v1` release) come from [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords)
via [`tools/gen_lang.py`](tools/gen_lang.py); Mandarin pinyin (`dicts/zh.txt`) is the Chinese list
converted to romanized pinyin by [`tools/gen_pinyin.py`](tools/gen_pinyin.py). The English and Hebrew
typing models (`app/src/main/res/raw/charmodel.bin`, `hebcharmodel.bin`) come from
[`tools/gen_charmodel.py`](tools/gen_charmodel.py) and [`tools/gen_hebrew.py`](tools/gen_hebrew.py).

## Releasing

Pushing a version tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml), which
builds, signs, and attaches the APK to a GitHub Release (Obtainium reads that).

One-time setup: create a signing keystore — keep it safe, losing it breaks future updates; it is
gitignored — then store it as repo secrets.

```sh
# Generate the keystore (prompts for a password and a name; the key password can match the store one)
keytool -genkeypair -v -keystore release.jks -alias lightkb \
    -keyalg RSA -keysize 2048 -validity 10000

# Copy its base64 for the KEYSTORE_BASE64 secret (macOS)
base64 -i release.jks | pbcopy
```

Add four secrets under **Settings → Secrets and variables → Actions**: `KEYSTORE_BASE64` (the value
just copied), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`lightkb`), and `KEY_PASSWORD`.

Then for each release, bump `versionCode`/`versionName` in `app/build.gradle` and push a tag:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

## Credits

Type is built on [**light-keyboard**](https://github.com/adam-weber/light-keyboard) by
[**Adam Weber**](https://github.com/adam-weber) — the original black-and-white Light Phone keyboard
recreation, and the foundation this project stands on. Type extends it with more languages, on-device
voice dictation, modular downloadable dictionaries, next-word prediction, and other polish. Huge thanks
to Adam for the groundwork. 🙏

## A note

This is an open-source project, made for the Light Phone but not by Light.

## License

[MIT](LICENSE) — © Adam Weber. Do what you like with it.
