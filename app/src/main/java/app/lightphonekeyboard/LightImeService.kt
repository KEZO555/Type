package app.lightphonekeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import kotlin.math.abs

/**
 * The system keyboard. Once enabled + selected as default, it appears in every text field on the
 * phone. Keystrokes from [LightKeyboardView] are applied to the focused field via InputConnection.
 *
 * Optional word-level autocorrect (toggle in [SetupActivity]) runs on top: when a word is finished
 * (space / punctuation / enter) we look it up in the active language's frequency dictionary (via
 * [Dictionaries], plus your learned words) and swap in the most likely fix. Case is preserved, and the
 * first backspace after a correction reverts it.
 */
class LightImeService : InputMethodService(), LightKeyboardView.Listener {

    private var keyboard: LightKeyboardView? = null

    private val dictation by lazy { VoiceDictation(this) }          // English: offline Vosk
    private val sysDictation by lazy { SystemDictation(this) }      // Hebrew: platform recognizer

    // Current letters language code, mirrored from the keyboard (globe key). Autocorrect uses the
    // matching dictionary (English/Hebrew bundled today; other languages download in a later phase).
    private var langCode = "en"
    private val hebrew: Boolean get() = langCode == "he"

    // Revert-on-backspace: after a correction the text before the cursor ends with [undoFrom];
    // the next backspace restores [undoTo] instead of deleting a character. [undoWord] is the word the
    // user originally typed — learned if they revert (a strong "I meant this" signal).
    private var undoFrom: String? = null
    private var undoTo: String? = null
    private var undoWord: String? = null
    // If the pending revert is undoing an auto-finalized Hebrew ending, this is the medial-form word to
    // remember as "keep medial" so it's never auto-finalized again (e.g. קליפ).
    private var undoKeepMedial: String? = null

    private var micActive = false

    override fun onCreate() {
        super.onCreate()
        if (Prefs.voiceEnabled(this)) dictation.prepare()   // warm the model if voice is on (and downloaded)
    }

    override fun onCreateInputView(): View {
        val kb = LightKeyboardView(this)
        kb.listener = this
        keyboard = kb
        return kb
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard?.reset()
        micActive = false
        dictation.destroy()
        sysDictation.destroy()
        clearUndo()
        // The keyboard keeps its language across fields; sync ours and warm the dictionaries.
        langCode = keyboard?.langCode ?: "en"
        if (dictNeeded()) prepareDict(langCode)
        updateShift()
        updateSuggestions()
    }

    /** Warm the autocorrect dictionary for [code] (bundled languages always; downloadable ones only
     *  once the user has downloaded them — [WordDictionary.prepare] no-ops otherwise). */
    private fun prepareDict(code: String) {
        Dictionaries.get(code)?.prepare(this)
    }

    override fun onDestroy() {
        dictation.destroy()
        sysDictation.destroy()
        super.onDestroy()
    }

    /** Globe key changed the letters language. Swap autocorrect engine and warm its dictionary. */
    override fun onLanguageChange(code: String) {
        langCode = code
        clearUndo()
        if (dictNeeded()) prepareDict(code)
        updateSuggestions()
    }

    /** Keep the keyboard's language in sync when the user switches our subtype via the system globe. */
    @Suppress("DEPRECATION")
    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        val tag = subtype?.languageTag?.takeIf { it.isNotEmpty() } ?: subtype?.locale.orEmpty()
        val he = tag.startsWith("he") || tag.startsWith("iw")   // "iw" is the legacy Hebrew code
        langCode = if (he) "he" else "en"
        keyboard?.setLanguageCode(langCode)
        clearUndo()
        if (dictNeeded()) prepareDict(langCode)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        updateShift() // after each keystroke/cursor move, recompute uppercase-vs-lowercase
        updateSuggestions()
    }

    /** Sentence-case: uppercase at a sentence start, lowercase after — from the field's caps mode. */
    private fun updateShift() {
        if (!Prefs.autoCap(this)) return
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: return
        keyboard?.setShifted(ic.getCursorCapsMode(type) != 0)
    }

    /** Long-press the globe → open the keyboard's settings screen. */
    override fun onOpenSettings() {
        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun textBeforeCursor(n: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(n, 0)

    // ------------------------------------------------------------------ key events

    override fun onText(s: String) {
        val ic = currentInputConnection ?: return
        if (s.length == 1 && isWordChar(s[0])) {
            clearUndo()
            // A letter continues the word, so a preceding Hebrew *final* form is no longer word-final.
            fixMedialBeforeTyping(s[0])
            ic.commitText(s, 1)
            return
        }
        // Only whitespace / sentence punctuation finish a word for autocorrect. Digits and other
        // symbols just commit, so alphanumeric tokens (ab2, mp3, covid19) are left alone.
        if (!(s.length == 1 && isCorrectTrigger(s[0]))) {
            clearUndo()
            ic.commitText(s, 1)
            return
        }
        // A word terminator: snap a trailing medial Hebrew letter to its final form, then autocorrect.
        val finalizedMedial = fixFinalForWordEnd()
        val original = trailingWord()
        val fix = if (autocorrectOn()) autocorrectFix(original) else null
        // A non-null fix means `original` is unfamiliar (sits one edit from a real word). The first time
        // we offer the correction in case it's a typo; once you've used the same unfamiliar word again,
        // we trust it — learn it and leave it alone from now on.
        val correct = fix != null && !fix.equals(original, ignoreCase = true) && !registerUnknownUse(original)
        if (correct) {
            val cased = applyCase(original, fix!!)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.commitText(s, 1)
            ic.endBatchEdit()
            undoFrom = cased + s     // arm revert: text now ends with the fix + terminator
            undoTo = original + s
            undoWord = original      // if the user reverts, learn what they actually typed
            undoKeepMedial = null
        } else {
            ic.commitText(s, 1)
            learnTyped(original)    // user typed this word and kept it — remember it
            if (finalizedMedial != null) {
                // Arm a revert: backspace restores the medial ending AND remembers to keep it medial.
                val finWord = finalizedMedial.dropLast(1) + medialToFinal[finalizedMedial.last()]
                undoFrom = finWord + s
                undoTo = finalizedMedial + s
                undoWord = null
                undoKeepMedial = finalizedMedial
            } else {
                clearUndo()
            }
        }
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val from = undoFrom
        val to = undoTo
        if (from != null && to != null) {
            val before = ic.getTextBeforeCursor(from.length, 0)?.toString()
            val word = undoWord
            val keepMedial = undoKeepMedial
            clearUndo()
            if (before == from) {   // only revert if the corrected text is still sitting there
                ic.beginBatchEdit()
                ic.deleteSurroundingText(from.length, 0)
                ic.commitText(to, 1)
                ic.endBatchEdit()
                if (word != null) learnTyped(word)              // user rejected the fix — learn their word
                if (keepMedial != null) Prefs.addKeepMedial(this, keepMedial)  // …or the medial ending
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        // Delete a whole grapheme cluster, not one UTF-16 unit — otherwise an emoji (surrogate pair /
        // variation selector) gets half-deleted and the leftover renders as a white box.
        val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBACK, 0)
        ic.deleteSurroundingText(lastGraphemeLength(before), 0)
    }

    override fun onBackspaceWord() {
        val ic = currentInputConnection ?: return
        clearUndo()
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        if (before.isEmpty()) { ic.deleteSurroundingText(1, 0); return }
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--    // trailing whitespace
        while (i > 0 && !before[i - 1].isWhitespace()) i--   // then the word
        val count = before.length - i
        ic.deleteSurroundingText(if (count > 0) count else 1, 0)
    }

    /** Length (in chars) of the last grapheme cluster of [before]; 1 if empty/unknown. */
    private fun lastGraphemeLength(before: CharSequence?): Int {
        if (before.isNullOrEmpty()) return 1
        val s = before.toString()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.last()
        val start = it.previous()
        return if (start == java.text.BreakIterator.DONE) s.length else (end - start)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        fixFinalForWordEnd()
        // Fix the last word before firing the action / newline.
        val original = trailingWord()
        val fix = if (autocorrectOn()) autocorrectFix(original) else null
        val correct = fix != null && !fix.equals(original, ignoreCase = true) && !registerUnknownUse(original)
        if (correct) {
            val cased = applyCase(original, fix!!)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.endBatchEdit()
        } else {
            learnTyped(original)   // user kept this word — remember it
        }
        clearUndo()
        // Enter inserts a real newline by default. Only fields that ask to submit/advance on Enter
        // (Go / Search / Send / Next) get their action — and not if the field opts out with
        // IME_FLAG_NO_ENTER_ACTION. A plain "Done" action used to just dismiss the keyboard; we now
        // treat that as a newline so Enter never closes the keyboard. (Long-press Enter hides it.)
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val submits = action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH ||
            action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_NEXT
        if (submits && (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    override fun onDismiss() {
        requestHideSelf(0) // swipe-down closes the keyboard, the proper Android way
    }

    /** Double-tap space → turn a trailing "<letter> " into "<letter>. " (only after a word char). */
    override fun onDoubleSpace() {
        val ic = currentInputConnection ?: return
        clearUndo()
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)   // remove the lone trailing space
            ic.commitText(". ", 1)
            ic.endBatchEdit()
        } else {
            ic.commitText(" ", 1)            // nothing to punctuate — just a space
        }
    }

    /** Space-bar swipe → nudge the caret one character per step (negative = left). */
    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        clearUndo()
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
    }

    /** Space-bar swipe up/down → move the caret one line. */
    override fun onCursorVertical(down: Boolean) {
        val ic = currentInputConnection ?: return
        clearUndo()
        val key = if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    // Hebrew letters with distinct word-final forms. Typed text uses the medial form; we snap to the
    // final form at a word end, and back to medial when the word turns out to continue.
    private val medialToFinal = mapOf('כ' to 'ך', 'מ' to 'ם', 'נ' to 'ן', 'פ' to 'ף', 'צ' to 'ץ')
    private val finalToMedial = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    // A geresh / apostrophe marks a transliterated foreign word (ג׳ = j, צ׳ = ch, ז׳ = zh, ת׳, ד׳ …).
    // Those keep their medial letter at the end — e.g. ג׳יפ, צ׳יפ, ג׳ירף — because the final form would
    // change the sound (final ף is "f", but "jeep" ends in a "p"). So we don't auto-finalize them.
    private fun isForeignWordEnd(before: String): Boolean {
        var i = before.length
        while (i > 0 && (before[i - 1] in 'א'..'ת' || before[i - 1] in "׳'״’")) i--
        return before.substring(i).any { it == '׳' || it == '\'' || it == '״' || it == '’' }
    }

    /** The trailing Hebrew word (letters + geresh/apostrophe) of [before], in whatever form it's in. */
    private fun trailingHebrewWord(before: String): String {
        var i = before.length
        while (i > 0 && (before[i - 1] in 'א'..'ת' || before[i - 1] in "׳'״’")) i--
        return before.substring(i)
    }

    /** At a word end: if the last letter is a medial form with a final variant, snap it to final, and
     *  return the medial-form word that was snapped (for revert/learning). Skips geresh-marked foreign
     *  words and any word the user has chosen to keep medial; returns null when nothing was changed. */
    private fun fixFinalForWordEnd(): String? {
        if (!hebrew) return null
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(24, 0)?.toString().orEmpty()
        val fin = before.lastOrNull()?.let { medialToFinal[it] } ?: return null
        if (isForeignWordEnd(before)) return null
        val word = trailingHebrewWord(before)                 // medial form, e.g. "קליפ"
        if (word in Prefs.keepMedial(this)) return null        // user corrected this one before
        ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(fin.toString(), 1); ic.endBatchEdit()
        return word
    }

    /** Before appending [next]: if a final form sits before the cursor, the word continues, so convert
     *  it back to its medial form — unless [next] repeats that same final letter (e.g. חלוםםם), which
     *  means the user deliberately wants the final form, so we leave it untouched. */
    private fun fixMedialBeforeTyping(next: Char) {
        if (!hebrew) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val prev = before.firstOrNull() ?: return
        if (next == prev) return                       // repeated same final letter → keep it final
        val med = finalToMedial[prev] ?: return
        ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(med.toString(), 1); ic.endBatchEdit()
    }

    // Never take over the whole screen with the big white "extract" editor (it appears in landscape by
    // default). Our keyboard is built for the compact LightOS layout, so keep it docked at the bottom.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onMic() {
        if (!Prefs.voiceEnabled(this)) return   // mic key is hidden when voice is off, but guard anyway
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // An IME can't pop the permission dialog itself; the shim activity does it.
            startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val kb = keyboard ?: return
        micActive = true
        kb.startListeningUi()
        if (hebrew) startHebrewDictation(kb) else startDictationWhenReady(kb, attempts = 0)
    }

    /** Hebrew dictation through the platform recognizer (no model download; needs the recognizer present). */
    private fun startHebrewDictation(kb: LightKeyboardView) {
        if (!micActive) return
        if (!sysDictation.available) {
            micActive = false
            kb.setListeningStatus("Voice unavailable")
            kb.postDelayed({ kb.stopListeningUi() }, 1200)
            return
        }
        sysDictation.listen(
            SystemDictation.HEBREW,
            onPartial = { kb.setListeningStatus(it) },
            onSegment = { text ->
                clearUndo()
                currentInputConnection?.commitText(spacedDictation(text), 1)
            },
            onError = { msg ->
                micActive = false
                kb.setListeningStatus(msg)
                kb.postDelayed({ kb.stopListeningUi() }, 1200)
            },
        )
    }

    /** Wait (briefly) for the model to finish unpacking on first use, then start listening. */
    private fun startDictationWhenReady(kb: LightKeyboardView, attempts: Int) {
        if (!micActive) return
        if (dictation.ready) {
            dictation.listen(
                onPartial = { kb.setListeningStatus(it) },
                // Each finished segment commits to the field; dictation keeps going across pauses.
                onSegment = { text ->
                    clearUndo()
                    currentInputConnection?.commitText(spacedDictation(text), 1)
                },
                onError = { msg ->
                    micActive = false
                    kb.setListeningStatus(msg)
                    kb.postDelayed({ kb.stopListeningUi() }, 1200)
                },
            )
            return
        }
        dictation.prepare()
        if (attempts > 40) {   // ~12s; first-run model unpack should be done well before this
            micActive = false
            kb.setListeningStatus("Voice unavailable")
            kb.postDelayed({ kb.stopListeningUi() }, 1200)
            return
        }
        kb.setListeningStatus("Preparing voice…")
        kb.postDelayed({ startDictationWhenReady(kb, attempts + 1) }, 300)
    }

    /** Tap on the listening surface = "I'm done": flush the trailing words, then close it. */
    override fun onMicCancel() {
        if (!micActive) return
        micActive = false
        dictation.stop()
        sysDictation.stop()
        keyboard?.stopListeningUi()
    }

    /** Insert a leading space if the cursor isn't already at a boundary, so dictated text doesn't fuse. */
    private fun spacedDictation(text: String): String {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return if (before.isNotEmpty() && !before.last().isWhitespace()) " $text" else text
    }

    // Tell any of our own overlays (e.g. light-assistant's edge seam) to get out of the way while the
    // keyboard is on screen, so they don't sit over the top-left keys.
    override fun onWindowShown() { super.onWindowShown(); broadcastImeVisible(true) }
    override fun onWindowHidden() { super.onWindowHidden(); broadcastImeVisible(false) }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        currentInputConnection?.finishComposingText()
        if (micActive) { micActive = false; dictation.destroy(); sysDictation.destroy(); keyboard?.stopListeningUi() }
        keyboard?.setSuggestions(emptyList())
        broadcastImeVisible(false)
    }

    private fun broadcastImeVisible(visible: Boolean) {
        runCatching { sendBroadcast(Intent(ACTION_IME_VISIBILITY).putExtra(EXTRA_VISIBLE, visible)) }
    }

    // ------------------------------------------------------------------ autocorrect

    private fun autocorrectOn(): Boolean = Prefs.autocorrect(this)

    /** A dictionary is needed if either autocorrect or the suggestion bar is on. */
    private fun dictNeeded(): Boolean = Prefs.autocorrect(this) || Prefs.suggestions(this)

    /** Recompute the word completions for what's being typed and push them to the suggestion bar. */
    private fun updateSuggestions() {
        val kb = keyboard ?: return
        if (!Prefs.suggestions(this)) { kb.setSuggestions(emptyList()); return }
        val word = trailingWord()
        val sugg = if (word.length >= 2) Dictionaries.get(langCode)?.completions(word, 3).orEmpty() else emptyList()
        kb.setSuggestions(sugg)
    }

    /** A suggestion-bar word was tapped: replace the word being typed with it (case-matched) + a space. */
    override fun onSuggestionPicked(word: String) {
        val ic = currentInputConnection ?: return
        clearUndo()
        val original = trailingWord()
        val cased = applyCase(original, word)
        ic.beginBatchEdit()
        if (original.isNotEmpty()) ic.deleteSurroundingText(original.length, 0)
        ic.commitText("$cased ", 1)
        ic.endBatchEdit()
        learnTyped(word)            // tapping a word counts as using it
        // onUpdateSelection fires from the commit and refreshes the bar (now empty — the word is done).
    }

    /**
     * The autocorrection for a finished [word], or null: the highest-frequency real word within a small
     * edit distance of what you typed (+ your learned words). A language whose dictionary isn't loaded
     * — e.g. a downloadable one you haven't downloaded — returns null (no correction).
     */
    private fun autocorrectFix(word: String): String? {
        if (!autocorrectOn() || word.length < 3) return null
        return Dictionaries.get(langCode)?.correct(word)
    }

    /** Remember a word the user typed, in the active language's dictionary. Gated on the dictionary
     *  being loaded so we don't clobber the saved learned-words file before it's read, nor hoard words
     *  for a downloadable language the user hasn't set up. */
    private fun learnTyped(word: String) {
        if (word.length < 2) return
        Dictionaries.get(langCode)?.takeIf { it.ready }?.learn(this, word)
    }

    // How many times an unfamiliar word has been typed this session (cleared if it grows large). Lets us
    // tell a one-off typo (correct it) from a word you actually use (learn it on the repeat).
    private val unknownUses = HashMap<String, Int>()

    /** Count one use of an unfamiliar [word]; returns true once it has been used enough to be trusted
     *  as a real word — at which point it's learned (in EN or HE) so it's never autocorrected again. */
    private fun registerUnknownUse(word: String): Boolean {
        if (word.length < 2) return false
        if (unknownUses.size > 500) unknownUses.clear()
        val key = "$langCode:$word"
        val n = (unknownUses[key] ?: 0) + 1
        unknownUses[key] = n
        if (n >= LEARN_AFTER_USES) {
            unknownUses.remove(key)
            learnTyped(word)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ helpers

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''

    /** Characters that "finish" a word and may trigger autocorrect — whitespace + sentence punctuation. */
    private fun isCorrectTrigger(c: Char): Boolean = c.isWhitespace() || c in ".,!?;:)"

    /** The run of word characters immediately before the cursor. */
    private fun trailingWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0) ?: return ""
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.substring(i).toString()
    }

    /** Match the suggestion's case to what the user typed (ALL CAPS / Capitalized / lower). */
    private fun applyCase(original: String, fix: String): String = when {
        original.length > 1 && original.all { it.isUpperCase() } -> fix.uppercase()
        original.firstOrNull()?.isUpperCase() == true -> fix.replaceFirstChar { it.uppercaseChar() }
        else -> fix
    }

    private fun clearUndo() {
        undoFrom = null
        undoTo = null
        undoWord = null
        undoKeepMedial = null
    }

    companion object {
        /** Broadcast so our overlays can dodge the keyboard. Implicit; caught by a runtime receiver. */
        const val ACTION_IME_VISIBILITY = "app.lightphonekeyboard.IME_VISIBILITY"
        const val EXTRA_VISIBLE = "visible"
        /** Window of text to inspect when deleting the last grapheme cluster (covers long emoji). */
        private const val GRAPHEME_LOOKBACK = 16
        /** Use an unfamiliar word this many times → it's added to your vocabulary (and stops correcting). */
        private const val LEARN_AFTER_USES = 2
    }
}
