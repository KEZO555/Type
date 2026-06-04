package app.lightphonekeyboard

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone emoji picker. A grid of candidate emoji (built from weighted LinearLayout rows, which size
 * reliably — unlike GridLayout column weights); tapping toggles whether each appears in the keyboard's
 * emoji panel (selected = full opacity on a subtle fill, hidden = dimmed). Saved live to [Prefs].
 * Chromed in the LightOS template style (see [LightUi]).
 */
class EmojiSettingsActivity : AppCompatActivity() {

    private val cols = 8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LightUi.screen(this, getString(R.string.setup_emoji)) { content ->
            LightUi.hint(content, getString(R.string.setup_emoji_sub))
            content.addView(
                buildGrid(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (16 * resources.displayMetrics.density).toInt() },
            )
        })
    }

    private fun buildGrid(): LinearLayout {
        val density = resources.displayMetrics.density
        val selected = LinkedHashSet(Prefs.emojiSet(this).ifEmpty { LightKeyboardView.EMOJI_DEFAULT })
        // One subtle rounded panel behind the whole grid; selection is shown by opacity, not per-cell
        // boxes, so it stays clean and minimal.
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(0xFF161616.toInt())
            }
        }
        val cands = LightKeyboardView.EMOJI_CANDIDATES
        var i = 0
        while (i < cands.size) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                if (i < cands.size) {
                    rowView.addView(cell(cands[i], selected, density), weightParams())
                } else {
                    rowView.addView(View(this), weightParams())   // pad the last row so columns align
                }
                i++
            }
            container.addView(
                rowView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return container
    }

    private fun weightParams() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun cell(glyph: String, selected: MutableSet<String>, density: Float): TextView {
        val cell = TextView(this).apply {
            text = glyph
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding((4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt())
            isClickable = true
        }
        fun render() { cell.alpha = if (glyph in selected) 1f else 0.22f }  // dim = hidden
        cell.setOnClickListener {
            if (!selected.remove(glyph)) selected.add(glyph)
            Prefs.setEmojiSet(this, selected.toList())
            render()
        }
        render()
        return cell
    }
}
