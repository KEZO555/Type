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
 * (space / punctuation / enter) we look it up in the bundled frequency dictionary for the active
 * language ([EnglishWords] / [HebrewDictionary], plus your learned words) and swap in the most likely
 * fix. Case is preserved, and the first backspace after a correction reverts it.
 */
class LightImeService : InputMethodService(), LightKeyboardView.Listener {

    private var keyboard: LightKeyboardView? = null

    private val dictation by lazy { VoiceDictation(this) }          // English: offline Vosk
    private val sysDictation by lazy { SystemDictation(this) }      // Hebrew: platform recognizer

    // Current letters language, mirrored from the keyboard (globe key). Autocorrect uses the bundled
    // English or Hebrew dictionary accordingly.
    private var hebrew = false

    // Revert-on-backspace: after a correction the text before the cursor ends with [undoFrom];
    // the next backspace restores [undoTo] instead of deleting a character. [undoWord] is the word the
    // user originally typed — learned if they revert (a strong "I meant this" signal).
    private var undoFrom: String? = null
    private var undoTo: String? = null
    private var undoWord: String? = null

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
        hebrew = keyboard?.isHebrew ?: false
        if (Prefs.autocorrect(this)) {
            EnglishWords.prepare(this)
            HebrewDictionary.prepare(this)
        }
        ghost = ""
        updateShift()
    }

    override fun onDestroy() {
        dictation.destroy()
        sysDictation.destroy()
        super.onDestroy()
    }

    /** Globe key flipped the letters language. Swap autocorrect engines and warm the Hebrew dictionary. */
    override fun onLanguageChange(hebrew: Boolean) {
        this.hebrew = hebrew
        clearUndo()
        if (hebrew) HebrewDictionary.prepare(this)
        clearGhost()
    }

    /** Keep the keyboard's language in sync when the user switches our subtype via the system globe. */
    @Suppress("DEPRECATION")
    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        val tag = subtype?.languageTag?.takeIf { it.isNotEmpty() } ?: subtype?.locale.orEmpty()
        val he = tag.startsWith("he") || tag.startsWith("iw")   // "iw" is the legacy Hebrew code
        hebrew = he
        keyboard?.setLanguage(he)
        clearUndo()
        if (he) HebrewDictionary.prepare(this)
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
    }

    // ------------------------------------------------------------------ inline word completion
    //
    // As you type a word we show the best completion as an underlined "composing" suffix right after
    // the cursor (Android won't let a keyboard paint gray ghost text in other apps — composing text is
    // the closest the platform allows). Pressing space accepts it; any other key drops it.

    private var ghost = ""   // the suffix currently shown as composing text (empty = none)

    /** Remove the inline completion preview, if any, without committing it. */
    private fun clearGhost() {
        if (ghost.isEmpty()) return
        currentInputConnection?.setComposingText("", 1)
        ghost = ""
    }

    /** Commit the inline completion into the text (cursor ends up after it). */
    private fun acceptGhost() {
        val ic = currentInputConnection ?: return
        if (ghost.isEmpty()) return
        ic.beginBatchEdit()
        ic.setComposingText("", 1)   // drop the composing preview…
        ic.commitText(ghost, 1)      // …and commit it as real text
        ic.endBatchEdit()
        ghost = ""
    }

    /** Sentence-case: uppercase at a sentence start, lowercase after — from the field's caps mode. */
    private fun updateShift() {
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: return
        keyboard?.setShifted(ic.getCursorCapsMode(type) != 0)
    }

    override fun textBeforeCursor(n: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(n, 0)

    // ------------------------------------------------------------------ key events

    override fun onText(s: String) {
        val ic = currentInputConnection ?: return
        // Inline completion: space accepts the preview; anything else drops it.
        if (s == " " && ghost.isNotEmpty()) acceptGhost() else clearGhost()
        if (s.length == 1 && isWordChar(s[0])) {
            clearUndo()
            // A letter continues the word, so a preceding Hebrew *final* form is no longer word-final.
            fixMedialBeforeTyping()
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
        fixFinalForWordEnd()
        val original = trailingWord()
        val fix = if (autocorrectOn()) autocorrectFix(original) else null
        if (fix != null && !fix.equals(original, ignoreCase = true)) {
            val cased = applyCase(original, fix)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.commitText(s, 1)
            ic.endBatchEdit()
            undoFrom = cased + s     // arm revert: text now ends with the fix + terminator
            undoTo = original + s
            undoWord = original      // if the user reverts, learn what they actually typed
        } else {
            clearUndo()
            ic.commitText(s, 1)
            learnTyped(original)    // user typed this word and kept it — remember it
        }
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        clearGhost()
        val from = undoFrom
        val to = undoTo
        if (from != null && to != null) {
            val before = ic.getTextBeforeCursor(from.length, 0)?.toString()
            val word = undoWord
            clearUndo()
            if (before == from) {   // only revert if the corrected text is still sitting there
                ic.beginBatchEdit()
                ic.deleteSurroundingText(from.length, 0)
                ic.commitText(to, 1)
                ic.endBatchEdit()
                if (word != null) learnTyped(word)   // user rejected the fix — learn their word
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
        clearGhost()
        fixFinalForWordEnd()
        // Fix the last word before firing the action / newline.
        val original = trailingWord()
        val fix = if (autocorrectOn()) autocorrectFix(original) else null
        if (fix != null && !fix.equals(original, ignoreCase = true)) {
            val cased = applyCase(original, fix)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.endBatchEdit()
        } else {
            learnTyped(original)   // user kept this word — remember it
        }
        clearUndo()
        // Honor the field's action (Send/Search/Go); otherwise insert a newline.
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
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

    // Edit menu (long-press space). The platform performs these against the focused field.
    override fun onSelectAll() { clearGhost(); currentInputConnection?.performContextMenuAction(android.R.id.selectAll) }
    override fun onCopy() { clearGhost(); currentInputConnection?.performContextMenuAction(android.R.id.copy) }
    override fun onCut() { clearGhost(); currentInputConnection?.performContextMenuAction(android.R.id.cut) }
    override fun onPaste() { clearGhost(); clearUndo(); currentInputConnection?.performContextMenuAction(android.R.id.paste) }

    /** Space-bar swipe → nudge the caret one character per step (negative = left). */
    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        clearGhost()
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
        clearGhost()
        clearUndo()
        val key = if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    // Hebrew letters with distinct word-final forms. Typed text uses the medial form; we snap to the
    // final form at a word end, and back to medial when the word turns out to continue.
    private val medialToFinal = mapOf('כ' to 'ך', 'מ' to 'ם', 'נ' to 'ן', 'פ' to 'ף', 'צ' to 'ץ')
    private val finalToMedial = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    /** At a word end: if the last letter is a medial form with a final variant, snap it to final. */
    private fun fixFinalForWordEnd() {
        if (!hebrew) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val fin = before.firstOrNull()?.let { medialToFinal[it] } ?: return
        ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(fin.toString(), 1); ic.endBatchEdit()
    }

    /** Before appending a letter: if a final form sits before the cursor, the word continues, so
     *  convert it back to its medial form. */
    private fun fixMedialBeforeTyping() {
        if (!hebrew) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val med = before.firstOrNull()?.let { finalToMedial[it] } ?: return
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
        clearGhost()
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
        ghost = ""
        currentInputConnection?.finishComposingText()
        if (micActive) { micActive = false; dictation.destroy(); sysDictation.destroy(); keyboard?.stopListeningUi() }
        broadcastImeVisible(false)
    }

    private fun broadcastImeVisible(visible: Boolean) {
        runCatching { sendBroadcast(Intent(ACTION_IME_VISIBILITY).putExtra(EXTRA_VISIBLE, visible)) }
    }

    // ------------------------------------------------------------------ autocorrect

    private fun autocorrectOn(): Boolean = Prefs.autocorrect(this)

    /**
     * The autocorrection for a finished [word], or null. Both languages use their bundled
     * frequency dictionary (+ your learned words): the highest-frequency real word within a small
     * edit distance of what you typed. Consistent, offline, and the same for English and Hebrew.
     */
    private fun autocorrectFix(word: String): String? {
        if (!autocorrectOn() || word.length < 3) return null
        return if (hebrew) HebrewDictionary.correct(word) else EnglishWords.correct(word)
    }

    /** Remember a word the user typed, in the active language's prediction dictionary. */
    private fun learnTyped(word: String) {
        if (word.length < 2) return
        if (hebrew) HebrewDictionary.learn(this, word) else EnglishWords.learn(this, word)
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
    }

    companion object {
        /** Broadcast so our overlays can dodge the keyboard. Implicit; caught by a runtime receiver. */
        const val ACTION_IME_VISIBILITY = "app.lightphonekeyboard.IME_VISIBILITY"
        const val EXTRA_VISIBLE = "visible"
        /** Window of text to inspect when deleting the last grapheme cluster (covers long emoji). */
        private const val GRAPHEME_LOOKBACK = 16
    }
}
