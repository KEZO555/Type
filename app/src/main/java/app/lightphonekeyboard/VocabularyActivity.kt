package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * "My words" — the vocabulary you've taught the keyboard (words it learned as you typed), grouped by
 * language. Tap a word to forget it; or clear everything. Reads the per-language learned dictionaries.
 */
class VocabularyActivity : AppCompatActivity() {

    private val maxPerLang = 400

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val pad = (24 * resources.displayMetrics.density).toInt()
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

        root.addView(label(getString(R.string.setup_vocab), 28f, R.color.white))
        root.addView(label(getString(R.string.setup_vocab_sub), 14f, R.color.gray))

        var total = 0
        // English then Hebrew (bundled), then any downloadable language you've taught words to.
        total += addSection(root, Languages.EN.name, EnglishWords.learnedWords(this)) { EnglishWords.forget(this, it) }
        total += addSection(root, Languages.HE.name, HebrewDictionary.learnedWords(this)) { HebrewDictionary.forget(this, it) }
        for (l in Languages.ALL) {
            val dict = if (l.dictUrl != null) Dictionaries.get(l.code) else null
            if (dict != null) total += addSection(root, l.name, dict.learnedWords(this)) { dict.forget(this, it) }
        }

        if (total == 0) {
            root.addView(label(getString(R.string.vocab_empty), 16f, R.color.gray))
        } else {
            val clear = Button(this).apply {
                text = getString(R.string.vocab_clear)
                isAllCaps = false
                textSize = 18f
                setTextColor(getColor(R.color.white))
                setBackgroundColor(getColor(R.color.black))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, pad)
                setOnClickListener {
                    EnglishWords.clearLearned(this@VocabularyActivity)
                    HebrewDictionary.clearLearned(this@VocabularyActivity)
                    for (l in Languages.ALL) if (l.dictUrl != null) Dictionaries.get(l.code)?.clearLearned(this@VocabularyActivity)
                    recreate()
                }
            }
            root.addView(clear)
        }

        return ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        }
    }

    /** Add a language header + its words (tap to forget). Returns how many words were shown. */
    private fun addSection(root: LinearLayout, name: String, words: List<String>, forget: (String) -> Unit): Int {
        if (words.isEmpty()) return 0
        val pad = (24 * resources.displayMetrics.density).toInt()
        root.addView(TextView(this).apply {
            text = "$name  ·  ${words.size}"
            setTextColor(getColor(R.color.white))
            textSize = 18f
            setPadding(0, pad, 0, pad / 4)
        })
        for (w in words.take(maxPerLang)) {
            val row = TextView(this).apply {
                text = w
                setTextColor(getColor(R.color.white))
                textSize = 18f
                setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, (10 * resources.displayMetrics.density).toInt())
                isClickable = true
                setOnClickListener {
                    forget(w)
                    alpha = 0.25f
                    isClickable = false
                }
            }
            root.addView(row)
        }
        if (words.size > maxPerLang) {
            root.addView(TextView(this).apply {
                text = getString(R.string.vocab_truncated, words.size - maxPerLang)
                setTextColor(getColor(R.color.gray))
                textSize = 13f
                setPadding(0, pad / 4, 0, 0)
            })
        }
        return words.size
    }
}
