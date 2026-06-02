package app.lightphonekeyboard

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone emoji picker. A grid of candidate emoji (built from weighted LinearLayout rows, which size
 * reliably — unlike GridLayout column weights); tapping toggles whether each appears in the keyboard's
 * emoji panel (selected = full opacity on a subtle fill, hidden = dimmed). Saved live to [Prefs].
 */
class EmojiSettingsActivity : AppCompatActivity() {

    private val cols = 8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(color))
            textSize = size
            setPadding(0, pad / 3, 0, pad / 3)
        }
        root.addView(label(getString(R.string.setup_emoji), 28f, R.color.white))
        root.addView(label(getString(R.string.setup_emoji_sub), 14f, R.color.gray))
        root.addView(buildGrid())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    private fun buildGrid(): LinearLayout {
        val density = resources.displayMetrics.density
        val selected = LinkedHashSet(Prefs.emojiSet(this).ifEmpty { LightKeyboardView.EMOJI_DEFAULT })
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
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
            textSize = 24f
            gravity = Gravity.CENTER
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            isClickable = true
        }
        fun render() {
            val on = glyph in selected
            cell.alpha = if (on) 1f else 0.3f
            cell.background = if (on) GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(0xFF2C2C2C.toInt())
            } else null
        }
        cell.setOnClickListener {
            if (!selected.remove(glyph)) selected.add(glyph)
            Prefs.setEmojiSet(this, selected.toList())
            render()
        }
        render()
        return cell
    }
}
