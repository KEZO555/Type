<img src="assets/images/example.png" alt="Light Keyboard">

A clone of the Light Phone 3's built-in keyboard, for any app.

On a stock Light Phone, the black-and-white keyboard lives inside Light's own tools. Other apps use the
system keyboard, and there is no Light one to choose. This is a faithful recreation, packaged as a
system keyboard you can set as the default, so every app on a modified Light Phone shares the same look.

Optional autocorrect and optional voice dictation. Swipe down on the keyboard to hide it.

**English and Hebrew.** The globe key switches between them; the emoji key opens emoji. Each language
has its own dictionary, autocorrect, and voice input:

| | English | Hebrew |
|---|---|---|
| Layout | QWERTY | Standard Israeli (SI-1452), finals included |
| Autocorrect | Bundled offline dictionary + edit-distance corrector (learns your words) | Bundled offline dictionary + edit-distance corrector (learns your words) |
| Voice | Offline, on-device (Vosk) — fully private | Phone's own voice service (`he-IL`) — may need a connection |

(Vosk has no Hebrew model, so Hebrew dictation uses Android's speech recognizer instead of the offline
English path. The English experience stays fully on-device.)

## Install

### With [Obtainium](https://github.com/ImranR98/Obtainium), which keeps you up to date

1. Install Obtainium.
2. Add an app, and give it this repository:
   `https://github.com/KEZO555/light-keyboard`
3. It installs the latest release, and tells you when there is a new one.

### Or by hand

Download the latest APK from [Releases](../../releases) and open it.

## Turn it on

Open Light Keyboard. The setup screen holds everything:

1. **Enable Light Keyboard**: opens Android's keyboard settings, where you switch it on.
2. **Choose it as your keyboard**: makes it the active one.

The settings below it:

- **Languages**: English and Hebrew. The globe key (bottom row) switches between them; the emoji key
  opens emoji. Hebrew is caseless, so there's no shift key — just the 27 letter forms in the standard
  Israeli arrangement.
- **Autocorrect** (on by default): fixes misspellings as you type, using bundled English and Hebrew
  frequency dictionaries that also learn the words you use. Keyboard-aware (it prefers fixes that are a
  neighbouring-key slip or a swapped pair) and conservative, so it doesn't replace words you meant.
  Fully offline. Turn it off to type exactly what you tap.
- **Auto-capitalize** (on by default): capitalize the first letter of each sentence.
- **Double-space for period** (on by default): tap space twice to insert `. `.
- **Haptic feedback**: cycle Off / Light / Medium / Strong; tapping previews the strength.
- **Number row** (off by default): a persistent row of digits above the letters.
- **Voice dictation** (off by default): turning it on downloads the ~40 MB offline English model once,
  then a mic key lets you speak instead of type. English runs entirely on-device; Hebrew dictation uses
  the phone's own voice service. Once downloaded, a **Delete voice model** button appears to reclaim the
  space.

That is the whole setup.

## Gestures

- **Globe** (marked with a small ⚙) — tap switches English ⇄ Hebrew; **long-press opens a quick-settings
  panel** right on the keyboard (haptic strength, autocorrect, auto-capitalize, double-space, number row,
  plus a shortcut to the full settings) — no need to leave the app you're typing in. **Comma key** —
  tap types a comma; **long-press opens the emoji panel** (recently-used float to the front).
- **Long-press a letter** — types its corner number/symbol directly.
- **Long-press the `123`/`ABC` key** (marked with `◌ָ` in Hebrew, `á` in English) — a picker of vowel
  points (Hebrew) / accented letters (English).
- **Long-press the period** — starts voice dictation (English only; Hebrew dictation needs a system
  recognizer most Light Phones don't have).
- **Double-tap space** — inserts `. ` (sentence end).
- **Drag the space bar** — moves the cursor like an iPhone trackpad: left/right by character, up/down
  by line.
- **Long-press the `123`/`ABC` key** — opens an edit menu: select all · copy · cut · paste.
- **Hold backspace** — repeats, then deletes whole words after a longer hold.
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
[`.github/workflows/release.yml`](.github/workflows/release.yml). The English typing model in
`app/src/main/res/raw/charmodel.bin` is regenerated by [`tools/gen_charmodel.py`](tools/gen_charmodel.py).
The Hebrew typing model (`app/src/main/res/raw/hebcharmodel.bin`) and the bundled Hebrew dictionary
(`app/src/main/assets/he_words.txt`) are regenerated by [`tools/gen_hebrew.py`](tools/gen_hebrew.py)
from a `word<space>frequency` list (e.g. the Hebrew list from
[hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords)). The English prediction list
(`app/src/main/assets/en_words.txt`) is built by [`tools/gen_english.py`](tools/gen_english.py) from a
frequency list such as [Norvig's count_1w.txt](https://norvig.com/ngrams/count_1w.txt).

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

## A note

This is an independent, open-source project. It is made for the Light Phone, but it is not made by Light.

## License

[MIT](LICENSE). Do what you like with it.
