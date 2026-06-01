package app.lightphonekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Black-and-white keyboard view, matched to the LightOS keyboard. UI-only: it reports key events
 * through [Listener]; the host [LightImeService] applies them to the focused field via InputConnection.
 *
 * This is a single self-drawing surface rather than one Android view per key. That matters for
 * typing accuracy:
 *   - Key hit rects *tile the whole surface with no gaps* (the visible gutters are drawn inside each
 *     cell), so there are no dead zones — every pixel, including the very corners, maps to a key.
 *     A point that somehow lands outside all rects snaps to the nearest key center.
 *   - Keys commit on touch-DOWN, and the key is locked in at down. The old per-view onClick fired on
 *     UP and cancelled if the finger drifted off the key — which is exactly what happens when you
 *     "roll" between keys typing fast, so letters were being dropped. Committing on down removes both
 *     the latency and the drift-cancellation.
 *   - Touches are tracked per pointer, so overlapping/rolling presses each register.
 *
 * Swipe DOWN anywhere on the keyboard closes it ([Listener.onDismiss]).
 *
 * Future: Apple-style dynamic target resizing (silently growing the hit rects of likely next letters
 * from a language model while the visible keys stay put) would build on this tiled-rect foundation.
 */
class LightKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onText(s: String)
        fun onBackspace()
        /** Delete the previous whole word (key-repeat escalates to this after a long hold). */
        fun onBackspaceWord()
        fun onEnter()
        fun onDismiss()
        /** Up to [n] characters immediately before the cursor, for the typing-accuracy context model. */
        fun textBeforeCursor(n: Int): CharSequence?
        /** Mic key tapped — start voice dictation. */
        fun onMic()
        /** Listening surface tapped — cancel dictation. */
        fun onMicCancel()
        /** Globe key toggled the letters language (true = Hebrew). Lets the host pick the right
         *  autocorrect engine and dictation backend. */
        fun onLanguageChange(hebrew: Boolean)
        /** Double-tap on space — turn the trailing space into ". " (sentence end). */
        fun onDoubleSpace()
        /** Space-bar swipe — move the caret by [steps] (negative = left, positive = right). */
        fun onCursorMove(steps: Int)
        /** Edit actions from the long-press-space menu. */
        fun onSelectAll()
        fun onCopy()
        fun onCut()
        fun onPaste()
    }

    var listener: Listener? = null

    private object Key {
        const val SHIFT = "__SHIFT__"
        const val BACKSPACE = "__BKSP__"
        const val SPACE = "__SPACE__"
        const val ENTER = "__ENTER__"
        const val GLOBE = "__GLOBE__"   // switches English ⇄ Hebrew
        const val EMOJI = "__EMOJI__"   // opens the emoji panel
        const val PERIOD = "."          // period; long-press starts voice dictation
        const val MIC = "__MIC__"       // (icon reused by the voice listening overlay)
        const val SYMBOLS = "123"
        const val LETTERS = "ABC"
        const val MORE = "=\\<"
        const val COMMA = ","
    }

    // One bottom row in every mode, same key positions throughout: [toggle] · [emoji|comma] · globe ·
    // space · [. | ⌫] · enter. Only the context-appropriate keys change — the layer toggle (123/ABC),
    // the second key (emoji on letters, comma elsewhere), and the fifth (period normally, but backspace
    // in the emoji panel, which has no letter row to delete from).
    private fun bottomRow(): List<String> {
        val toggle = if (layer == Layer.LETTERS) Key.SYMBOLS else Key.LETTERS
        val second = if (layer == Layer.LETTERS) Key.EMOJI else Key.COMMA
        val fifth = if (layer == Layer.EMOJI) Key.BACKSPACE else Key.PERIOD
        return listOf(toggle, second, Key.GLOBE, Key.SPACE, fifth, Key.ENTER)
    }

    private object Layout {
        val letters = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf(Key.SHIFT, "z", "x", "c", "v", "b", "n", "m", Key.BACKSPACE),
        )
        // Hebrew (standard Israeli layout, finals included; no case → no shift key). Row 1 leads with
        // geresh + maqaf so all three rows are full width, matching the system Hebrew keyboard.
        val hebrew = listOf(
            listOf("׳", "־", "ק", "ר", "א", "ט", "ו", "ן", "ם", "פ"),
            listOf("ש", "ד", "ג", "כ", "ע", "י", "ח", "ל", "ך", "ף"),
            listOf("ז", "ס", "ב", "ה", "נ", "מ", "צ", "ת", "ץ", Key.BACKSPACE),
        )
        // Optional persistent number row (prepended to the letters layers when enabled in setup).
        val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        // Symbols page 1, matching the system keyboard. =\< leads to page 2 (more).
        val symbols = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
            listOf(Key.MORE, "*", "\"", "'", ":", ";", "!", "?", Key.BACKSPACE),
        )
        // Symbols page 2 (the less-common marks); 123 leads back to page 1.
        val more = listOf(
            listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"),
            listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\"),
            listOf(Key.SYMBOLS, "%", "©", "®", "™", "[", "]", "<", ">", Key.BACKSPACE),
        )
        // The eight curated favourites first, then the twenty most-common emoji. Laid out as a grid
        // in the emoji panel (see layoutEmoji).
        val emoji = listOf(
            "🧞‍♂️", "🦾", "🪬", "👾", "🫶🏻", "🌸", "🫠", "🤌🏻",
            "😀", "😂", "🥹", "😍", "😎", "😭", "🥰", "😘", "😉", "🙃",
            "👍", "🙏", "🙌", "👀", "🔥", "❤️", "✨", "🎉", "💯", "🤔",
        )
    }

    private enum class Layer { LETTERS, SYMBOLS, MORE, EMOJI }

    enum class Lang { EN, HE }

    private var lang = Lang.EN
    val isHebrew: Boolean get() = lang == Lang.HE

    private var layer = Layer.LETTERS
    private var shifted = true
    private var capsLock = false           // double-tap shift → stays uppercase until tapped off
    private var lastShiftTapMs = 0L

    // Voice-dictation listening overlay (drawn instead of keys while the recognizer is active).
    private var listening = false
    private var listeningStatus = ""

    // Backspace held → repeat deleting chars, then escalate to whole words after a long hold.
    private var backspacePointerId = -1
    private var backspaceDownMs = 0L
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - backspaceDownMs >= BACKSPACE_WORD_AFTER_MS) {
                listener?.onBackspaceWord()
                postDelayed(this, BACKSPACE_WORD_INTERVAL_MS)
            } else {
                listener?.onBackspace()
                postDelayed(this, BACKSPACE_CHAR_INTERVAL_MS)
            }
        }
    }

    // Long-press a letter → a popup of alternates (English accents / Hebrew niqqud). The base char is
    // committed on down as usual; choosing a non-base option retracts it and commits the choice.
    private var longPressPointerId = -1
    private var longPressCandidate: PlacedKey? = null
    private var popupActive = false
    private var popupOptions: List<String> = emptyList()
    private var popupIndex = 0
    private var popupKey: PlacedKey? = null
    private var popupEdit = false                 // true = edit menu (actions), false = text alternates
    private val altLongPress = Runnable { showAltPopup() }

    // Long-press the space bar → an edit menu. Indices match EDIT_ACTIONS order.
    private val EDIT_ACTIONS = listOf("Select all", "Copy", "Cut", "Paste")

    // Space-bar gestures: double-tap → ". ", horizontal drag → move the caret.
    private var spacePointerId = -1
    private var spaceDownX = 0f
    private var spaceSwiping = false
    private var spaceSwipeRefX = 0f
    private var lastSpaceTapMs = 0L

    // English accent alternates, and the Hebrew niqqud (vowel points) offered on any Hebrew letter.
    private object Alt {
        val en = mapOf(
            'a' to "àáâäãå", 'e' to "èéêëē", 'i' to "ìíîïī", 'o' to "òóôöõø",
            'u' to "ùúûü", 'n' to "ñ", 'c' to "ç", 's' to "ß", 'y' to "ýÿ", 'z' to "ž",
        )
        // Combining marks: patah, qamats, segol, tsere, hiriq, holam, sheva, dagesh.
        val niqqud = listOf('ַ', 'ָ', 'ֶ', 'ֵ', 'ִ', 'ֹ', 'ְ', 'ּ')
    }

    /** One key with its (gapless) hit rect and its inset, drawn-to rect. */
    private class PlacedKey(val id: String, val hit: RectF, val vis: RectF) {
        val cx get() = vis.centerX()
        val cy get() = vis.centerY()
    }

    private val placed = ArrayList<PlacedKey>()
    private val letterKeys = ArrayList<PlacedKey>()   // a-z keys only, for the accuracy model

    // --- metrics (px) ---
    private val padTop = dpf(3)
    private val padBottom = dpf(10)
    private val padSide = dpf(6)
    private val keyGap = dpf(3)        // half the visible gutter; applied as an inset on each side
    private val rowKeyH = dpf(43)
    private val rowPitch = rowKeyH + keyGap * 2   // ~49dp per row

    private val emojiCols = 10
    private val emojiGridRows = (Layout.emoji.size + emojiCols - 1) / emojiCols  // 28 / 10 = 3

    // --- paints / icon cache ---
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    // Alternates popup: a dark rounded card with a white border; the selected cell is filled white.
    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 28) }
    private val popupBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val popupSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconCache = HashMap<Int, Drawable>()

    // --- touch tracking ---
    private val pressed = HashMap<Int, PlacedKey>()   // pointerId -> key, for the pressed highlight
    private var downX = 0f
    private var downY = 0f
    private var firstPointerId = -1
    private var firstKeyRetractable = false   // did the gesture's first tap commit a retractable char?
    private var dismissedThisGesture = false

    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        rebuild()
    }

    private val currentRows: List<List<String>>
        get() {
            if (layer == Layer.EMOJI) return emptyList()   // emoji is laid out separately
            var rows = when (layer) {
                Layer.LETTERS -> if (lang == Lang.HE) Layout.hebrew else Layout.letters
                Layer.SYMBOLS -> Layout.symbols
                Layer.MORE -> Layout.more
                Layer.EMOJI -> emptyList()
            }
            // Optional persistent number row sits above the letters (the symbols layer has its own).
            if (layer == Layer.LETTERS && Prefs.numberRow(context)) {
                rows = listOf(Layout.numberRow) + rows
            }
            return rows + listOf(bottomRow())   // shared [toggle] · emoji · globe · space · . · enter
        }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        // The emoji panel is laid out to the same row count as the letters layer, so the keyboard
        // keeps a constant height and doesn't jump when you switch to emoji.
        val rowCount = when {
            listening -> Layout.letters.size           // keep height constant while listening
            layer == Layer.EMOJI -> emojiGridRows + 1  // emoji rows + control row (= 4, same as letters)
            else -> currentRows.size
        }
        val h = padTop + rowCount * rowPitch + padBottom
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun rebuild() {
        relayout()
        requestLayout()
        invalidate()
    }

    private fun relayout() {
        placed.clear()
        letterKeys.clear()
        if (width == 0 || height == 0 || listening) return
        if (layer == Layer.EMOJI) { layoutEmoji(); return }

        val w = width.toFloat()
        val h = height.toFloat()
        val rows = currentRows
        val n = rows.size
        for (i in rows.indices) {
            // Bands tile [0, h]: the top row absorbs the top pad, the bottom row absorbs the bottom pad.
            val bandTop = if (i == 0) 0f else padTop + i * rowPitch
            val bandBottom = if (i == n - 1) h else padTop + (i + 1) * rowPitch
            val visTop = padTop + i * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            layoutRow(rows[i], bandTop, bandBottom, visTop, visBottom, w)
        }
        for (k in placed) if (isLetter(k.id)) letterKeys.add(k)
    }

    private fun layoutRow(
        row: List<String>, bandTop: Float, bandBottom: Float,
        visTop: Float, visBottom: Float, w: Float,
    ) {
        val totalWeight = row.sumOf { weightFor(it).toDouble() }.toFloat()
        val drawLeft = padSide
        val drawW = w - padSide * 2
        var cum = 0f
        for ((j, id) in row.withIndex()) {
            val cellLeft = drawLeft + drawW * (cum / totalWeight)
            cum += weightFor(id)
            val cellRight = drawLeft + drawW * (cum / totalWeight)
            // Hit rects tile [0, w]: edge keys reach the screen edge; interior boundaries sit on the
            // visible cell edge, i.e. the midline of the gutter between two keys (nearest-key by design).
            val hitLeft = if (j == 0) 0f else cellLeft
            val hitRight = if (j == row.size - 1) w else cellRight
            placed.add(
                PlacedKey(
                    id,
                    RectF(hitLeft, bandTop, hitRight, bandBottom),
                    RectF(cellLeft + keyGap, visTop, cellRight - keyGap, visBottom),
                ),
            )
        }
    }

    private fun layoutEmoji() {
        // Emoji grid fills the rows above a normal control row, using the same row geometry as the
        // letters layer so the keyboard height never jumps. The control row mirrors the letters bottom
        // row — a layer key, then the globe at the SAME index, then space — so the globe never moves;
        // it just swaps enter for backspace (deleting an emoji is what you want here).
        val w = width.toFloat()
        val h = height.toFloat()
        val drawW = w - padSide * 2
        val glyphs = displayedEmoji()
        for (r in 0 until emojiGridRows) {
            val bandTop = if (r == 0) 0f else padTop + r * rowPitch
            val bandBottom = padTop + (r + 1) * rowPitch
            val visTop = padTop + r * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            for (c in 0 until emojiCols) {
                val idx = r * emojiCols + c
                if (idx >= glyphs.size) break
                val cellLeft = padSide + drawW * (c.toFloat() / emojiCols)
                val cellRight = padSide + drawW * ((c + 1).toFloat() / emojiCols)
                val hitLeft = if (c == 0) 0f else cellLeft
                val hitRight = if (c == emojiCols - 1) w else cellRight
                placed.add(
                    PlacedKey(
                        glyphs[idx],
                        RectF(hitLeft, bandTop, hitRight, bandBottom),
                        RectF(cellLeft, visTop, cellRight, visBottom),
                    ),
                )
            }
        }

        // Same bottom row as every other mode (here the fifth key is backspace, so you can delete).
        val i = emojiGridRows
        val bandTop = padTop + i * rowPitch
        val visTop = bandTop + keyGap
        layoutRow(bottomRow(), bandTop, h, visTop, visTop + rowKeyH, w)
    }

    /** The curated emoji set, with recently-used ones pulled to the front (same 28 glyphs, reordered). */
    private fun displayedEmoji(): List<String> {
        val recents = Prefs.recentEmoji(context).filter { it in Layout.emoji }
        if (recents.isEmpty()) return Layout.emoji
        return recents + Layout.emoji.filter { it !in recents }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (listening) { drawListening(canvas); return }
        for (pk in placed) {
            if (pressed.containsValue(pk)) {
                val r = dpf(8)
                canvas.drawRoundRect(pk.vis, r, r, pressPaint)
            }
            drawKey(canvas, pk)
        }
        if (popupActive) drawAltPopup(canvas)
    }

    /** The alternates popup: a card of option cells above the long-pressed key (below it for the top
     *  row, where there's no room above). The selected cell is filled; slide to it and release. */
    private fun drawAltPopup(canvas: Canvas) {
        val opts = popupOptions
        val k = popupKey ?: return
        if (opts.isEmpty()) return
        val cellW = popupCellW(opts.size)
        val cellH = dpf(POPUP_CELL_H_DP)
        val totalW = cellW * opts.size
        val left = popupLeft(totalW)
        var top = k.vis.top - dpf(8) - cellH
        if (top < 0f) top = k.vis.bottom + dpf(8)     // top row → drop the card below the key
        val r = dpf(8)
        val card = RectF(left, top, left + totalW, top + cellH)
        canvas.drawRoundRect(card, r, r, popupBgPaint)
        canvas.drawRoundRect(card, r, r, popupBorderPaint)
        textPaint.textSize = if (popupEdit) spf(14) else spf(22)   // word labels need smaller text
        for (j in opts.indices) {
            val cl = left + cellW * j
            if (j == popupIndex) {
                val inset = dpf(3)
                val ir = dpf(6)
                canvas.drawRoundRect(cl + inset, top + inset, cl + cellW - inset, top + cellH - inset, ir, ir, popupSelPaint)
                textPaint.color = Color.BLACK
            } else {
                textPaint.color = Color.WHITE
            }
            val baseline = card.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(opts[j], cl + cellW / 2f, baseline, textPaint)
        }
        textPaint.color = Color.WHITE     // restore for subsequent draws
    }

    /** The voice-dictation surface: a big centered mic, the live status/partial text, and a hint. */
    private fun drawListening(canvas: Canvas) {
        val cx = width / 2f
        val midY = height / 2f
        val d = iconCache.getOrPut(R.drawable.ic_kb_mic) { context.getDrawable(R.drawable.ic_kb_mic)!! }
        val size = dpf(44)
        val left = (cx - size / 2f).toInt()
        val top = (midY - size - dpf(6)).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
        textPaint.textSize = spf(18)
        // Wrap the live text so a long phrase stacks into lines instead of running off the screen.
        drawWrappedCentered(canvas, listeningStatus, cx, midY + dpf(22), width - dpf(48), textPaint)
        textPaint.textSize = spf(12)
        canvas.drawText("Tap when done", cx, height - dpf(18), textPaint)
    }

    /** Draw [text] centered on ([cx],[centerY]), wrapping at word boundaries to fit [maxWidth]. */
    private fun drawWrappedCentered(
        canvas: Canvas, text: String, cx: Float, centerY: Float, maxWidth: Float, paint: Paint,
    ) {
        if (text.isEmpty()) return
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                lines.add(line); line = word
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        val lineH = paint.descent() - paint.ascent()
        var baseline = centerY - lines.size * lineH / 2f - paint.ascent()
        for (l in lines) { canvas.drawText(l, cx, baseline, paint); baseline += lineH }
    }

    private fun drawKey(canvas: Canvas, pk: PlacedKey) {
        val id = pk.id
        if (id == Key.SPACE) {
            // Plain centred line, matching the LightOS keyboard. (Double-tap inserts ". "; a horizontal
            // drag moves the caret — no label clutter on the bar itself.)
            val y = pk.vis.centerY()
            canvas.drawRect(pk.vis.left + dpf(28), y - dpf(1), pk.vis.right - dpf(28), y + dpf(1), spacePaint)
            return
        }
        val icon = iconFor(id)
        if (icon != null) {
            drawIcon(canvas, icon, pk.vis, padFor(id))
            // Caps-lock indicator: an underline beneath the shift glyph.
            if (id == Key.SHIFT && capsLock) {
                val cx = pk.vis.centerX()
                val y = pk.vis.centerY() + dpf(11)
                canvas.drawRect(cx - dpf(7), y - dpf(1), cx + dpf(7), y + dpf(1), spacePaint)
            }
            return
        }
        // Emoji glyphs are large; everything else (letters, the layer toggle, the period) is normal.
        val size = when {
            layer == Layer.EMOJI && id in Layout.emoji -> spf(20)
            id.length == 1 -> spf(23)
            else -> spf(18)
        }
        textPaint.textSize = size
        val baseline = pk.vis.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(labelFor(id), pk.vis.centerX(), baseline, textPaint)
        // The period doubles as the voice key (long-press) — show a small mic so it's discoverable.
        if (id == Key.PERIOD && Prefs.voiceEnabled(context)) {
            val s = dpf(15)
            val d = iconCache.getOrPut(R.drawable.ic_kb_mic) { context.getDrawable(R.drawable.ic_kb_mic)!! }
            val cx = pk.vis.centerX()
            val top = pk.vis.top + dpf(2)
            d.setBounds((cx - s / 2f).toInt(), top.toInt(), (cx + s / 2f).toInt(), (top + s).toInt())
            d.draw(canvas)
        }
    }

    private fun drawIcon(canvas: Canvas, res: Int, vis: RectF, pad: Float) {
        val d = iconCache.getOrPut(res) { context.getDrawable(res)!! }
        val size = (minOf(vis.width(), vis.height()) - pad * 2).coerceAtLeast(1f)
        val left = (vis.centerX() - size / 2f).toInt()
        val top = (vis.centerY() - size / 2f).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
    }

    private fun iconFor(id: String): Int? = when (id) {
        Key.GLOBE -> R.drawable.ic_kb_globe
        Key.EMOJI -> R.drawable.ic_kb_emoji
        Key.BACKSPACE -> R.drawable.ic_kb_backspace
        Key.ENTER -> R.drawable.ic_kb_enter
        Key.MIC -> R.drawable.ic_kb_mic
        Key.SHIFT -> if (shifted) R.drawable.ic_kb_chevron_down else R.drawable.ic_kb_chevron_up
        else -> null
    }

    // Smaller inset = larger glyph. The bottom-row icons (globe / emoji / enter) are roomy so they
    // read clearly; shift keeps a touch more breathing room for its caps-lock underline.
    private fun padFor(id: String): Float = when (id) {
        Key.SHIFT -> dpf(7)
        Key.GLOBE, Key.EMOJI, Key.ENTER -> dpf(2)
        Key.BACKSPACE -> dpf(4)
        else -> dpf(6)
    }

    // Only English has letter case; Hebrew letters are returned verbatim (uppercase() is a no-op on
    // them anyway, but the explicit guard keeps the intent clear). The "back to letters" key shows a
    // Hebrew label when Hebrew is the active letters language.
    private fun labelFor(id: String): String = when {
        id == Key.LETTERS && lang == Lang.HE -> "אבג"
        lang == Lang.EN && shifted && layer == Layer.LETTERS && id.length == 1 && id[0].isLetter() ->
            id.uppercase()
        else -> id
    }

    private fun weightFor(id: String): Float = when (id) {
        Key.SPACE -> 5f
        // Only the bottom-row layer toggle is wide. =\< stays normal width so the symbols row-3
        // backspace lines up with the letters row-3 backspace.
        Key.SYMBOLS, Key.LETTERS -> 1.4f
        else -> 1f
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (listening) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { tap(); listener?.onMicCancel() }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dismissedThisGesture = false
                firstPointerId = ev.getPointerId(0)
                firstKeyRetractable = pressDown(firstPointerId, ev.x, ev.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                pressDown(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx))
            }

            MotionEvent.ACTION_MOVE -> {
                // 1) Alternates popup open → the long-press pointer's x selects the option.
                if (popupActive) {
                    val pidx = ev.findPointerIndex(longPressPointerId)
                    if (pidx >= 0) { updatePopupSelection(ev.getX(pidx), ev.getY(pidx)); invalidate() }
                    return true
                }
                // 2) Space-bar horizontal drag → caret movement.
                if (spacePointerId != -1) {
                    val sidx = ev.findPointerIndex(spacePointerId)
                    if (sidx >= 0 && handleSpaceSwipe(ev.getX(sidx))) return true
                }
                // 3) Otherwise, the first pointer's downward swipe dismisses the keyboard.
                if (!dismissedThisGesture) {
                    val idx = ev.findPointerIndex(firstPointerId)
                    if (idx >= 0) {
                        val dy = ev.getY(idx) - downY
                        val dx = ev.getX(idx) - downX
                        if (dy > dpf(60) && dy > abs(dx) * 1.5f) {
                            dismissedThisGesture = true
                            stopBackspaceRepeat()
                            endAltLongPress()
                            // The first tap already committed a char on down; retract it so the swipe
                            // doesn't leave a stray letter behind.
                            if (firstKeyRetractable) listener?.onBackspace()
                            pressed.clear()
                            invalidate()
                            listener?.onDismiss()
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pid = ev.getPointerId(ev.actionIndex)
                if (pid == longPressPointerId) commitPopupAndReset()
                pressed.remove(pid)
                if (pid == backspacePointerId) stopBackspaceRepeat()
                if (pid == spacePointerId) spacePointerId = -1
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ev.actionMasked == MotionEvent.ACTION_UP) commitPopupAndReset() else endAltLongPress()
                pressed.clear()
                stopBackspaceRepeat()
                spacePointerId = -1
                invalidate()
            }
        }
        return true
    }

    /** Space-bar drag → caret moves one step per [SPACE_SWIPE_STEP]. Returns true once swiping starts
     *  (the first step retracts the space that was committed on touch-down). */
    private fun handleSpaceSwipe(x: Float): Boolean {
        if (!spaceSwiping) {
            if (abs(x - spaceDownX) < SPACE_SWIPE_START) return false
            spaceSwiping = true
            spaceSwipeRefX = spaceDownX
            endAltLongPress()
            if (firstPointerId == spacePointerId && firstKeyRetractable) {
                listener?.onBackspace()           // remove the space inserted on down
                firstKeyRetractable = false
            }
        }
        var moved = false
        while (x - spaceSwipeRefX >= SPACE_SWIPE_STEP) { listener?.onCursorMove(1); spaceSwipeRefX += SPACE_SWIPE_STEP; moved = true }
        while (x - spaceSwipeRefX <= -SPACE_SWIPE_STEP) { listener?.onCursorMove(-1); spaceSwipeRefX -= SPACE_SWIPE_STEP; moved = true }
        if (moved) cursorTick()
        return true
    }

    /** A gentle tick for caret movement — much softer than the key-press haptic. */
    private fun cursorTick() {
        val effect = if (android.os.Build.VERSION.SDK_INT >= 27)
            HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK
        performHapticFeedback(effect)
    }

    /** Resolve the key under a pointer, commit it immediately, and light it up. */
    private fun pressDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (dismissedThisGesture) return false
        val raw = findKey(x, y) ?: return false
        // Only letters get the accuracy treatment; control keys & other layers stay exact hit-testing.
        val key = if (layer == Layer.LETTERS && isLetter(raw.id)) resolveLetter(x, y, raw) else raw
        pressed[pointerId] = key
        invalidate()
        val retractable = onKey(key.id)
        if (key.id == Key.BACKSPACE) {           // first delete fired on down; now arm the repeat
            backspacePointerId = pointerId
            backspaceDownMs = System.currentTimeMillis()
            removeCallbacks(backspaceRepeat)
            postDelayed(backspaceRepeat, BACKSPACE_INITIAL_DELAY_MS)
        }
        if (key.id == Key.SPACE) {               // track for double-tap (handled in onKey) + swipe
            spacePointerId = pointerId
            spaceDownX = x
            spaceSwiping = false
        }
        // Long-press: a letter → accents/niqqud popup; the period → voice; space → edit menu.
        if (alternatesFor(key.id) != null ||
            (key.id == Key.PERIOD && Prefs.voiceEnabled(context)) ||
            key.id == Key.SPACE
        ) {
            longPressPointerId = pointerId
            longPressCandidate = key
            removeCallbacks(altLongPress)
            postDelayed(altLongPress, ALT_LONGPRESS_MS)
        }
        return retractable
    }

    private fun stopBackspaceRepeat() {
        backspacePointerId = -1
        removeCallbacks(backspaceRepeat)
    }

    // ------------------------------------------------------------------ alternates popup

    /** The long-press popup options for [id] (base first, then accents/niqqud), or null if none. */
    private fun alternatesFor(id: String): List<String>? {
        if (layer != Layer.LETTERS || id.length != 1) return null
        val ch = id[0]
        val accents: List<String> = when {
            lang == Lang.HE && ch in spec -> Alt.niqqud.map { id + it }
            lang == Lang.EN -> Alt.en[ch.lowercaseChar()]?.let { extra ->
                (if (shifted) extra.uppercase() else extra).map { it.toString() }
            } ?: emptyList()
            else -> emptyList()
        }
        return if (accents.isEmpty()) null else listOf(labelFor(id)) + accents
    }

    private fun showAltPopup() {
        val k = longPressCandidate ?: return
        // Long-press the period → start voice dictation (retract the '.' committed on down).
        if (k.id == Key.PERIOD) {
            endAltLongPress()
            if (Prefs.voiceEnabled(context)) { tap(); listener?.onBackspace(); listener?.onMic() }
            return
        }
        // Long-press space → edit menu. Retract the space committed on down; it wasn't wanted.
        if (k.id == Key.SPACE) {
            if (firstPointerId == spacePointerId && firstKeyRetractable) {
                listener?.onBackspace(); firstKeyRetractable = false
            }
            popupEdit = true
            popupOptions = EDIT_ACTIONS
        } else {
            popupEdit = false
            popupOptions = alternatesFor(k.id) ?: return
        }
        popupActive = true
        popupKey = k
        popupIndex = if (popupEdit) -1 else 0     // edit: nothing selected until you slide into it
        tap()
        invalidate()
    }

    /** Popup cell width. The edit menu uses wide cells (word labels); alternates use compact cells. */
    private fun popupCellW(n: Int): Float {
        val avail = (width - padSide * 2) / n.coerceAtLeast(1)
        return if (popupEdit) avail else minOf(dpf(POPUP_CELL_DP), avail)
    }

    /** Pick the option under finger x. For the edit menu, only select once the finger slides up into
     *  the menu (above the key) — so a plain hold-and-release doesn't fire an action. */
    private fun updatePopupSelection(x: Float, y: Float) {
        val opts = popupOptions
        if (opts.isEmpty()) return
        if (popupEdit && (popupKey == null || y > popupKey!!.vis.top)) { popupIndex = -1; return }
        val cellW = popupCellW(opts.size)
        val totalW = cellW * opts.size
        val left = popupLeft(totalW)
        popupIndex = (((x - left) / cellW).toInt()).coerceIn(0, opts.size - 1)
    }

    /** Commit the popup selection and reset. For the edit menu, run the chosen action; for text
     *  alternates, index 0 is the base char (already typed → no change). */
    private fun commitPopupAndReset() {
        if (popupActive) {
            val opts = popupOptions
            if (popupEdit) {
                when (popupIndex) {
                    0 -> listener?.onSelectAll()
                    1 -> listener?.onCopy()
                    2 -> listener?.onCut()
                    3 -> listener?.onPaste()
                }
                tap()
            } else if (popupIndex in 1 until opts.size) {
                listener?.onBackspace()              // retract the base committed on down
                listener?.onText(opts[popupIndex])
                tap()
            }
        }
        endAltLongPress()
    }

    private fun endAltLongPress() {
        removeCallbacks(altLongPress)
        longPressPointerId = -1
        longPressCandidate = null
        if (popupActive) {
            popupActive = false; popupEdit = false; popupKey = null; popupOptions = emptyList(); invalidate()
        }
    }

    /** Left edge of the popup card, clamped on-screen. */
    private fun popupLeft(totalW: Float): Float {
        val cx = popupKey?.vis?.centerX() ?: (width / 2f)
        return (cx - totalW / 2f).coerceIn(padSide, (width - padSide - totalW).coerceAtLeast(padSide))
    }

    /** Tiled rects always contain the point; the nearest-center fallback only covers off-surface taps. */
    private fun findKey(x: Float, y: Float): PlacedKey? {
        if (placed.isEmpty()) return null
        val cx = x.coerceIn(0f, width - 1f)
        val cy = y.coerceIn(0f, height - 1f)
        placed.firstOrNull { it.hit.contains(cx, cy) }?.let { return it }
        return placed.minByOrNull { val dx = it.cx - cx; val dy = it.cy - cy; dx * dx + dy * dy }
    }

    // ------------------------------------------------------------------ typing accuracy
    //
    // Per-tap key selection = spatial likelihood (Gaussian on distance) × language likelihood
    // (a character trigram model, frequency-weighted English). For an ambiguous tap near a key
    // boundary this lets context break the tie (after "th", a tap between e/r/w resolves to "e").
    // A confident tap inside a key's core is returned directly, so deliberate taps are never
    // overridden. Distances are normalised by key width / row pitch so both axes are comparable.

    /** Holds ln P(c3 | c1,c2) over an alphabet of [syms] symbols (letters + a word boundary). */
    private class CharModel(private val logp: FloatArray, val syms: Int) {
        fun lp(c1: Int, c2: Int, c3: Int): Float = logp[(c1 * syms + c2) * syms + c3]
    }

    /**
     * Per-language alphabet for the accuracy model. Letters occupy a contiguous Unicode block from
     * [base] (EN: a-z, 26; HE: א..ת incl. finals, 27); index [boundary] == [size] is the word-boundary
     * symbol, so the trigram table has [syms] symbols per axis — matching the .bin built by the tools.
     */
    private class LangSpec(val base: Char, val size: Int, val modelRes: Int) {
        val syms: Int get() = size + 1
        val boundary: Int get() = size
        operator fun contains(ch: Char): Boolean {
            val l = ch.lowercaseChar()      // no-op for Hebrew; folds EN uppercase from the field
            return l >= base && l.code < base.code + size
        }
        fun symIndex(ch: Char): Int {
            val i = ch.lowercaseChar().code - base.code
            return if (i in 0 until size) i else boundary
        }
    }

    private val specEn = LangSpec('a', 26, R.raw.charmodel)
    private val specHe = LangSpec('א', 27, R.raw.hebcharmodel)   // U+05D0 = א
    private val spec: LangSpec get() = if (lang == Lang.HE) specHe else specEn

    // Cache per language. containsKey (not getOrPut) so a failed load caches null instead of retrying
    // a resource read on every tap.
    private val charModels = HashMap<Lang, CharModel?>()
    private fun charModel(): CharModel? {
        if (!charModels.containsKey(lang)) charModels[lang] = loadCharModel(spec)
        return charModels[lang]
    }

    // Tunables. biasY shifts the effective touch point up because fingers tend to land low; if a
    // particular zone reads wrong, nudge these. LAMBDA scales how much context can sway a tap.
    private val biasX = 0f
    private val biasY = -dpf(6)
    private val coreFrac = 0.5f       // within this fraction of a key (normalised) → no override
    private val sigmaFrac = 0.72f     // Gaussian width in key units
    private val radiusFrac = 1.5f     // only score candidates within this many key units
    private val lambda = 1.0f

    private fun isLetter(id: String): Boolean = id.length == 1 && id[0] in spec

    private fun resolveLetter(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        if (letterKeys.isEmpty()) return raw
        val cx = x + biasX
        val cy = y + biasY
        val kw = raw.vis.width().coerceAtLeast(1f)
        // nearest letter key, in normalised (per-axis) distance
        var nearest = raw
        var nd2 = Float.MAX_VALUE
        for (k in letterKeys) {
            val d2 = norm2(k, cx, cy, kw)
            if (d2 < nd2) { nd2 = d2; nearest = k }
        }
        if (sqrt(nd2) < coreFrac) return nearest          // confident tap — leave it alone
        val model = charModel() ?: return nearest
        val (c1, c2) = contextSymbols()
        val sigma2 = 2f * sigmaFrac * sigmaFrac
        val radius2 = radiusFrac * radiusFrac
        var best = nearest
        var bestScore = -Float.MAX_VALUE
        for (k in letterKeys) {
            val d2 = norm2(k, cx, cy, kw)
            if (d2 > radius2) continue
            val score = -d2 / sigma2 + lambda * model.lp(c1, c2, spec.symIndex(k.id[0]))
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best
    }

    private fun norm2(k: PlacedKey, cx: Float, cy: Float, kw: Float): Float {
        val dx = (k.cx - cx) / kw
        val dy = (k.cy - cy) / rowPitch
        return dx * dx + dy * dy
    }

    /** The two symbols before the cursor (letter → 0..size-1, anything else / absent → boundary). */
    private fun contextSymbols(): Pair<Int, Int> {
        val s = listener?.textBeforeCursor(2)?.toString().orEmpty()
        val c1 = if (s.length >= 2) spec.symIndex(s[s.length - 2]) else spec.boundary
        val c2 = if (s.isNotEmpty()) spec.symIndex(s[s.length - 1]) else spec.boundary
        return c1 to c2
    }

    private fun loadCharModel(s: LangSpec): CharModel? = try {
        val bytes = resources.openRawResource(s.modelRes).use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        CharModel(arr, s.syms)
    } catch (e: Exception) {
        null   // fall back to pure nearest-key (spatial only) if the asset is missing/corrupt
    }

    /** Applies a key. Returns true if it committed a single retractable character (text or space). */
    private fun onKey(id: String): Boolean {
        tap()
        when (id) {
            Key.SHIFT -> { onShift(); rebuild() }
            Key.BACKSPACE -> listener?.onBackspace()
            Key.ENTER -> listener?.onEnter()
            Key.SYMBOLS -> { layer = Layer.SYMBOLS; rebuild() }
            Key.MORE -> { layer = Layer.MORE; rebuild() }
            Key.LETTERS -> { layer = Layer.LETTERS; rebuild() }
            Key.GLOBE -> toggleLang()
            Key.EMOJI -> { layer = Layer.EMOJI; rebuild() }
            Key.MIC -> listener?.onMic()
            Key.PERIOD -> { listener?.onText("."); return true }   // long-press → voice (handled in touch)
            Key.COMMA -> { listener?.onText(","); return true }
            Key.SPACE -> {
                val now = System.currentTimeMillis()
                if (now - lastSpaceTapMs < DOUBLE_TAP_MS) {   // double-tap → ". "
                    lastSpaceTapMs = 0L
                    listener?.onDoubleSpace()
                    return false
                }
                lastSpaceTapMs = now
                listener?.onText(" ")
                return true
            }
            else -> {
                if (layer == Layer.EMOJI) {
                    Prefs.pushRecentEmoji(context, id)   // float it to the front next time
                    listener?.onText(id)
                    return false
                }
                listener?.onText(labelFor(id))
                return true
            }
        }
        return false
    }

    /** Globe key: switch the letters between English and Hebrew (and snap back to the letters view if
     *  we were in emoji/symbols). The host is told so it can swap autocorrect engine + dictation. */
    private fun toggleLang() {
        lang = if (lang == Lang.HE) Lang.EN else Lang.HE
        if (lang == Lang.HE) { shifted = false; capsLock = false }
        layer = Layer.LETTERS
        listener?.onLanguageChange(lang == Lang.HE)
        rebuild()
    }

    /** Shift tap: toggles one-shot uppercase; a quick double-tap latches caps lock; tapping while
     *  locked clears it. */
    private fun onShift() {
        val now = System.currentTimeMillis()
        when {
            capsLock -> { capsLock = false; shifted = false }
            now - lastShiftTapMs < DOUBLE_TAP_MS -> { capsLock = true; shifted = true }
            else -> shifted = !shifted
        }
        lastShiftTapMs = now
    }

    /** Set the letters language from outside (e.g. an OS input-subtype switch). Unlike the globe key,
     *  this does NOT re-notify the host — the host is the one driving the change. */
    fun setLanguage(hebrew: Boolean) {
        val want = if (hebrew) Lang.HE else Lang.EN
        if (lang == want) return
        lang = want
        if (lang == Lang.HE) { shifted = false; capsLock = false }
        layer = Layer.LETTERS
        rebuild()
    }

    /** Reset to the default letters/uppercase view (called when a new field gains focus). */
    fun reset() {
        stopBackspaceRepeat()
        endAltLongPress()
        spacePointerId = -1; spaceSwiping = false
        layer = Layer.LETTERS; shifted = true; capsLock = false; listening = false; rebuild()
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        endAltLongPress()
        super.onDetachedFromWindow()
    }

    /** Enter the voice-dictation listening surface. */
    fun startListeningUi() { listening = true; listeningStatus = "Listening…"; rebuild() }

    /** Update the listening status / live partial transcription. */
    fun setListeningStatus(text: String) {
        if (!listening) return
        listeningStatus = text
        invalidate()
    }

    /** Leave the listening surface, back to keys. */
    fun stopListeningUi() {
        if (!listening) return
        listening = false
        rebuild()
    }

    /**
     * Sentence-case auto-shift, driven by the host IME from the field's caps mode: uppercase at a
     * sentence start, lowercase after the first letter. One-shot — a manual SHIFT tap holds only
     * until the next letter, after which the IME recomputes this.
     */
    fun setShifted(value: Boolean) {
        if (lang == Lang.HE) return     // Hebrew is caseless — no auto-shift
        if (capsLock) return            // caps lock overrides sentence-case auto-shift
        if (shifted != value) {
            shifted = value
            if (layer == Layer.LETTERS) rebuild()
        }
    }

    private fun tap() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private val DOUBLE_TAP_MS = 300L
    private val BACKSPACE_INITIAL_DELAY_MS = 400L   // pause before key-repeat kicks in
    private val BACKSPACE_CHAR_INTERVAL_MS = 95L    // per-character repeat rate
    private val BACKSPACE_WORD_AFTER_MS = 1500L     // after this long holding, delete whole words
    private val BACKSPACE_WORD_INTERVAL_MS = 190L   // per-word repeat rate
    private val ALT_LONGPRESS_MS = 300L             // hold a letter this long → accents/niqqud popup
    private val POPUP_CELL_DP = 38                  // alternates popup: cell width / height
    private val POPUP_CELL_H_DP = 46
    private val SPACE_SWIPE_START = dpf(18)         // horizontal travel before space-swipe engages
    private val SPACE_SWIPE_STEP = dpf(12)          // travel per one-character caret move

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
    private fun spf(v: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics)
}
