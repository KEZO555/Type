package app.lightphonekeyboard

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * "My words" — the vocabulary you've taught the keyboard, grouped by language. Tap a word to forget it,
 * or clear everything. LightOS template style (see [LightUi]).
 */
class VocabularyActivity : AppCompatActivity() {

    private val maxPerLang = 400

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        setContentView(LightUi.screen(this, getString(R.string.setup_vocab)) { content ->
            LightUi.hint(content, getString(R.string.setup_vocab_sub))
            val dicts = Languages.ALL.mapNotNull { l -> Dictionaries.get(l.code)?.let { l to it } }
            var total = 0
            for ((l, dict) in dicts) {
                val words = dict.learnedWords(this)
                if (words.isEmpty()) continue
                total += words.size
                sectionLabel(content, "${l.name}  ·  ${words.size}")
                for (w in words.take(maxPerLang)) wordRow(content, w) { dict.forget(this, w) }
                if (words.size > maxPerLang) {
                    LightUi.hint(content, getString(R.string.vocab_truncated, words.size - maxPerLang))
                }
            }
            if (total == 0) {
                LightUi.hint(content, getString(R.string.vocab_empty))
            } else {
                LightUi.navItem(content, getString(R.string.vocab_clear)) {
                    for ((_, dict) in dicts) dict.clearLearned(this)
                    render()
                }
            }
        })
    }

    private fun sectionLabel(parent: LinearLayout, text: String) {
        val d = resources.displayMetrics.density
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(getColor(R.color.gray))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (26 * d).toInt()
            layoutParams = lp
        })
    }

    /** A learned word as big text; tap to forget it (the row dims to confirm). */
    private fun wordRow(parent: LinearLayout, word: String, forget: () -> Unit) {
        val d = resources.displayMetrics.density
        val row = TextView(this).apply {
            text = word
            textSize = 24f
            setTextColor(getColor(R.color.white))
            isClickable = true
            setOnClickListener {
                forget()
                alpha = 0.25f
                isClickable = false
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (12 * d).toInt()
            layoutParams = lp
        }
        parent.addView(row)
    }
}
