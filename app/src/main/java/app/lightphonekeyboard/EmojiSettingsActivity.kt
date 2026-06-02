package app.lightphonekeyboard

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Standalone emoji picker. A scrollable grid of candidate emoji; tapping toggles whether each appears
 * in the keyboard's emoji panel (selected = full opacity on a subtle fill, hidden = dimmed). Saved live
 * to [Prefs]. Kept in its own screen so the main setup list stays short.
 */
class EmojiSettingsActivity : AppCompatActivity() {

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

    private fun buildGrid(): GridLayout {
        val density = resources.displayMetrics.density
        val selected = LinkedHashSet(Prefs.emojiSet(this).ifEmpty { LightKeyboardView.EMOJI_DEFAULT })
        val grid = GridLayout(this).apply {
            columnCount = 8
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        for (glyph in LightKeyboardView.EMOJI_CANDIDATES) {
            val cell = TextView(this).apply {
                text = glyph
                textSize = 24f
                gravity = Gravity.CENTER
                val p = (8 * density).toInt()
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
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.CENTER)
            })
        }
        return grid
    }
}
