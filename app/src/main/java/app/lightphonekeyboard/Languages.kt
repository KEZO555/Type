package app.lightphonekeyboard

/**
 * The keyboard's supported languages, as data. Each [LangDef] carries its letter layout, the accents
 * offered on the 123-key long-press, and (for English/Hebrew) the bundled autocorrect dictionary.
 *
 * Adding a language is just adding an entry here plus, for autocorrect, a downloadable dictionary
 * (wired in a later phase). The typing-accuracy trigram model and the final-letter logic stay
 * English/Hebrew-only — other languages fall back to plain hit-testing, which is perfectly usable.
 */
class LangDef(
    val code: String,
    val name: String,                       // native display name, e.g. "Español"
    val rtl: Boolean,
    val hasCase: Boolean,                   // Latin scripts have upper/lower; Hebrew does not
    val rows: List<List<String>>,           // three letter rows; the bottom row is shared by all modes
    val accents: List<String>,              // 123-key long-press picker (accents, or Hebrew vowel points)
    val lettersLabel: String = "ABC",       // label on the "back to letters" toggle key
    val dictAsset: String? = null,          // bundled autocorrect dictionary (English only); null otherwise
    val dictUrl: String? = null,            // downloadable autocorrect dictionary (es/fr/de/it/pt)
    val voiceUrl: String? = null,           // downloadable offline Vosk voice model (every language but Hebrew)
    val voiceSizeMb: Int = 0,               // approx download size of that model, for the settings hint
    private val hintsOverride: Map<Char, String>? = null,
) {
    /** Long-press a letter → its corner number/symbol. Positional by default; Hebrew overrides. */
    val hints: Map<Char, String> = hintsOverride ?: positionalHints(rows)

    /** The letter rows of this layout as lowercase strings (control keys and symbols dropped), used to
     *  build the key-adjacency model for keyboard-aware autocorrect. */
    val letterRows: List<String> = rows
        .map { row -> row.filter { it.length == 1 && it[0].isLetter() }.joinToString("") { it.lowercase() } }
        .filter { it.isNotEmpty() }

    /** The alphabet used to generate autocorrect edit candidates: every distinct lowercase letter on the
     *  layout, plus the lowercase of each accented form offered on the 123-key long-press (é, ñ, ü …). */
    val autocorrectAlphabet: String = buildString {
        val seen = HashSet<Char>()
        for (row in letterRows) for (ch in row) if (seen.add(ch)) append(ch)
        for (a in accents) for (ch in a.lowercase()) if (ch.isLetter() && seen.add(ch)) append(ch)
    }
}

object Languages {
    // Control-key markers — must match LightKeyboardView.Key.SHIFT / .BACKSPACE.
    private const val SH = "__SHIFT__"
    private const val BK = "__BKSP__"

    // On-demand autocorrect dictionaries are hosted on the project's own "dicts-v1" release: cleaned,
    // filtered "word<space>count" lists (~0.5 MB each) built by tools/gen_lang.py from
    // hermitdave/FrequencyWords. Self-hosting keeps downloads small and reliable (DictModel also
    // re-filters to the language's letters as a safety net). Kept off the APK so it stays small.
    private fun freqWordsUrl(code: String) =
        "https://github.com/KEZO555/light-keyboard/releases/download/dicts-v1/$code.txt"

    // Hebrew, Arabic and Mandarin-pinyin dictionaries live in the repo's dicts/ folder (served raw),
    // not on the dicts-v1 release. Downloaded + filtered to the language's letters on-device, same format.
    private fun repoDictUrl(code: String) =
        "https://raw.githubusercontent.com/KEZO555/light-keyboard/main/dicts/$code.txt"

    // Offline Vosk speech models (small variants), downloaded on demand like the dictionaries so they
    // stay off the APK. Hosted by the Vosk project. Hebrew has no Vosk model, so it has no entry here.
    private fun voskUrl(file: String) = "https://alphacephei.com/vosk/models/$file"

    private val QWERTY = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf(SH, "z", "x", "c", "v", "b", "n", "m", BK),
    )

    val EN = LangDef(
        code = "en", name = "English", rtl = false, hasCase = true,
        rows = QWERTY,
        accents = listOf("á", "é", "í", "ó", "ú", "à", "è", "ñ", "ç", "ü", "ö", "ä"),
        dictAsset = "en_words.txt",
        voiceUrl = voskUrl("vosk-model-small-en-us-0.15.zip"), voiceSizeMb = 40,
    )

    val HE = LangDef(
        code = "he", name = "עברית", rtl = false, hasCase = false,
        rows = listOf(
            listOf("׳", "-", "ק", "ר", "א", "ט", "ו", "ן", "ם", "פ"),
            listOf("ש", "ד", "ג", "כ", "ע", "י", "ח", "ל", "ך", "ף"),
            listOf("ז", "ס", "ב", "ה", "נ", "מ", "צ", "ת", "ץ", BK),
        ),
        // Combining marks: patah, qamats, segol, tsere, hiriq, holam, sheva, dagesh.
        accents = listOf("ַ", "ָ", "ֶ", "ֵ", "ִ", "ֹ", "ְ", "ּ"),
        lettersLabel = "אבג",
        dictUrl = repoDictUrl("he"),     // downloaded like the others (no longer bundled in the APK)
        hintsOverride = mapOf(
            '׳' to "1", '-' to "2", 'ק' to "3", 'ר' to "4", 'א' to "5",
            'ט' to "6", 'ו' to "7", 'ן' to "8", 'ם' to "9", 'פ' to "0",
            'ש' to "@", 'ד' to "#", 'ג' to "₪", 'כ' to "_", 'ע' to "&",
            'י' to "-", 'ח' to "+", 'ל' to "(", 'ך' to ")", 'ף' to "/",
            'ז' to "`", 'ס' to "*", 'ב' to "\"", 'ה' to "'", 'נ' to ";",
            'מ' to ":", 'צ' to "!", 'ת' to "?", 'ץ' to "\\",
        ),
    )

    val ES = LangDef(
        code = "es", name = "Español", rtl = false, hasCase = true,
        rows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ"),
            listOf(SH, "z", "x", "c", "v", "b", "n", "m", BK),
        ),
        accents = listOf("á", "é", "í", "ó", "ú", "ü", "ñ", "¿", "¡"),
        dictUrl = freqWordsUrl("es"),
        voiceUrl = voskUrl("vosk-model-small-es-0.42.zip"), voiceSizeMb = 39,
    )

    val FR = LangDef(
        code = "fr", name = "Français", rtl = false, hasCase = true,
        rows = listOf(
            listOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m"),
            listOf(SH, "w", "x", "c", "v", "b", "n", BK),
        ),
        accents = listOf("à", "â", "ç", "é", "è", "ê", "ë", "î", "ï", "ô", "û", "ù", "œ"),
        dictUrl = freqWordsUrl("fr"),
        voiceUrl = voskUrl("vosk-model-small-fr-0.22.zip"), voiceSizeMb = 41,
    )

    val DE = LangDef(
        code = "de", name = "Deutsch", rtl = false, hasCase = true,
        rows = listOf(
            listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf(SH, "y", "x", "c", "v", "b", "n", "m", BK),
        ),
        accents = listOf("ä", "ö", "ü", "ß"),
        dictUrl = freqWordsUrl("de"),
        voiceUrl = voskUrl("vosk-model-small-de-0.15.zip"), voiceSizeMb = 45,
    )

    val IT = LangDef(
        code = "it", name = "Italiano", rtl = false, hasCase = true,
        rows = QWERTY,
        accents = listOf("à", "è", "é", "ì", "ò", "ù"),
        dictUrl = freqWordsUrl("it"),
        voiceUrl = voskUrl("vosk-model-small-it-0.22.zip"), voiceSizeMb = 48,
    )

    val PT = LangDef(
        code = "pt", name = "Português", rtl = false, hasCase = true,
        rows = QWERTY,
        accents = listOf("ã", "õ", "á", "à", "â", "ç", "é", "ê", "í", "ó", "ô", "ú", "ü"),
        dictUrl = freqWordsUrl("pt"),
        voiceUrl = voskUrl("vosk-model-small-pt-0.3.zip"), voiceSizeMb = 31,
    )

    // Arabic — RTL and caseless like Hebrew (so no shift key). The text field handles direction; the
    // keyboard just commits letters. Standard Arabic arrangement; all 28 base letters across the three
    // rows (plus ة and ى), with hamza forms and harakat on the 123-key long-press. Typing-only for now
    // (no autocorrect dictionary, no voice). Western digits via the positional long-press hints.
    val AR = LangDef(
        code = "ar", name = "العربية", rtl = true, hasCase = false,
        rows = listOf(
            listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
            listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك"),
            listOf("ظ", "ط", "ذ", "د", "ز", "ر", "و", "ة", "ى", BK),
        ),
        accents = listOf("ء", "أ", "إ", "آ", "ؤ", "ئ", "ً", "ٌ", "ٍ", "َ", "ُ", "ِ", "ّ", "ْ"),
        lettersLabel = "ابج",
        dictUrl = repoDictUrl("ar"),     // offline autocorrect, downloaded on demand
    )

    // Mandarin as QWERTY pinyin: a Latin layout for typing romanized pinyin (no Hanzi conversion — that
    // would need a full candidate-selection IME). 'ü' (the one pinyin vowel not on QWERTY, plus its
    // toned forms) sits on the 123-key long-press. Typing-only — no autocorrect dictionary, no voice.
    val ZH = LangDef(
        code = "zh", name = "中文 (拼音)", rtl = false, hasCase = true,
        rows = QWERTY,
        accents = listOf("ü", "ǖ", "ǘ", "ǚ", "ǜ"),
        dictUrl = repoDictUrl("zh"),     // pinyin autocorrect + completion, downloaded on demand
    )

    // Dutch — two regional layouts that share one dictionary and voice model: Netherlands (QWERTY) and
    // Belgium/Flanders (AZERTY). Diacritics (trema + acute) on the 123-key long-press.
    private val DUTCH_ACCENTS = listOf("ë", "ï", "ö", "ü", "é", "è", "ê", "á", "à", "ç")
    val NL = LangDef(
        code = "nl", name = "Nederlands", rtl = false, hasCase = true,
        rows = QWERTY,
        accents = DUTCH_ACCENTS,
        dictUrl = repoDictUrl("nl"),
        voiceUrl = voskUrl("vosk-model-small-nl-0.22.zip"), voiceSizeMb = 39,
    )
    val NL_BE = LangDef(
        code = "nl-be", name = "Nederlands (AZERTY)", rtl = false, hasCase = true,
        rows = listOf(
            listOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m"),
            listOf(SH, "w", "x", "c", "v", "b", "n", BK),
        ),
        accents = DUTCH_ACCENTS,
        dictUrl = repoDictUrl("nl"),     // same Dutch dictionary as the QWERTY variant
        voiceUrl = voskUrl("vosk-model-small-nl-0.22.zip"), voiceSizeMb = 39,
    )

    // Russian — standard ЙЦУКЕН layout; ё and ъ (not on the main rows) on the 123-key long-press.
    val RU = LangDef(
        code = "ru", name = "Русский", rtl = false, hasCase = true,
        rows = listOf(
            listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"),
            listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"),
            listOf(SH, "я", "ч", "с", "м", "и", "т", "ь", "б", "ю", BK),
        ),
        accents = listOf("ё", "ъ"),
        lettersLabel = "АБВ",
        dictUrl = repoDictUrl("ru"),
        voiceUrl = voskUrl("vosk-model-small-ru-0.22.zip"), voiceSizeMb = 45,
    )

    // Polish — QWERTY with the Polish diacritics (ą ć ę ł ń ó ś ź ż) on the 123-key long-press.
    val PL = LangDef(
        code = "pl", name = "Polski", rtl = false, hasCase = true,
        rows = QWERTY,
        accents = listOf("ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"),
        dictUrl = repoDictUrl("pl"),
        voiceUrl = voskUrl("vosk-model-small-pl-0.22.zip"), voiceSizeMb = 50,
    )

    /** All supported languages, in the order the globe cycles them. */
    val ALL = listOf(EN, HE, ES, FR, DE, IT, PT, AR, ZH, NL, NL_BE, RU, PL)

    fun byCode(code: String): LangDef = ALL.firstOrNull { it.code == code } ?: EN
}

/** Build the corner number/symbol hints positionally from a Latin/QWERTY-style layout, matching the
 *  classic phone scheme: digits across the top row, then @#$_&-+()/ , then *"':;!? on the last row. */
private fun positionalHints(rows: List<List<String>>): Map<Char, String> {
    val out = HashMap<Char, String>()
    fun assign(row: List<String>?, syms: String, lettersOnly: Boolean) {
        if (row == null) return
        var s = 0
        for (key in row) {
            if (key.length != 1) continue
            if (lettersOnly && !key[0].isLetter()) continue
            if (s >= syms.length) break
            out[key[0]] = syms[s].toString()
            s++
        }
    }
    assign(rows.getOrNull(0), "1234567890", lettersOnly = false)
    assign(rows.getOrNull(1), "@#\$_&-+()/", lettersOnly = false)
    assign(rows.getOrNull(2), "*\"':;!?", lettersOnly = true)
    return out
}
