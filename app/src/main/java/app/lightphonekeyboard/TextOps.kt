package app.lightphonekeyboard

import java.text.BreakIterator

/**
 * Pure text helpers shared by the keyboard, with no Android dependencies so they can be unit-tested on
 * the host JVM (see TextOpsTest). [LightImeService] holds the InputConnection and calls into here for the
 * actual logic — word boundaries, autocorrect casing, grapheme-aware deletion, and the Hebrew
 * medial/final letter mapping — so the fiddly, easy-to-break rules have fast, automated coverage.
 */
object TextOps {

    /** A character that's part of a word for typing/autocorrect: any letter, plus the apostrophe. */
    fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''

    /** A character that "finishes" a word and may trigger autocorrect — whitespace + sentence punctuation. */
    fun isCorrectTrigger(c: Char): Boolean = c.isWhitespace() || c in ".,!?;:)"

    /**
     * Match a suggestion/correction's case to what the user typed: ALL CAPS stays all caps, a leading
     * capital stays capitalised, anything else is left as the dictionary's (lower) form.
     */
    fun applyCase(original: String, fix: String): String = when {
        original.length > 1 && original.all { it.isUpperCase() } -> fix.uppercase()
        original.firstOrNull()?.isUpperCase() == true -> fix.replaceFirstChar { it.uppercaseChar() }
        else -> fix
    }

    /** The run of word characters at the end of [before] (i.e. immediately before the cursor). */
    fun trailingWord(before: CharSequence): String {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.subSequence(i, before.length).toString()
    }

    /**
     * The completed word *before* the current one — used as next-word-prediction context. Skips the
     * trailing (possibly empty) word being typed, then the separators, then returns the word before
     * that. So "the qu" → "the" (while typing "qu"), and "the " → "the" (just after a space). Empty if
     * there's no such word.
     */
    fun precedingWord(before: CharSequence): String {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--      // skip the current (partial) word
        while (i > 0 && !isWordChar(before[i - 1])) i--     // skip the separators between the two words
        val end = i
        while (i > 0 && isWordChar(before[i - 1])) i--      // the preceding word
        return before.subSequence(i, end).toString()
    }

    /**
     * Length (in UTF-16 units) of the last grapheme cluster in [before], so backspace deletes a whole
     * user-perceived character — an emoji built from surrogate pairs, ZWJ sequences or variation
     * selectors comes off in one press instead of leaving a stray half that renders as a white box.
     * Returns 1 for null/empty so a backspace on an empty field still does the obvious thing.
     */
    fun lastGraphemeLength(before: CharSequence?): Int {
        if (before.isNullOrEmpty()) return 1
        val s = before.toString()
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE) s.length else (end - start)
    }

    // Hebrew letters with a distinct word-final form (sofit). Five letters change shape at a word's end;
    // the keyboard types the medial form and snaps to the final form when the word ends (and back if the
    // user keeps typing). These two maps are exact inverses of each other (asserted in TextOpsTest).
    val hebrewMedialToFinal = mapOf('כ' to 'ך', 'מ' to 'ם', 'נ' to 'ן', 'פ' to 'ף', 'צ' to 'ץ')
    val hebrewFinalToMedial = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')
}
