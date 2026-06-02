package app.lightphonekeyboard

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Languages screen: toggle which languages the globe key cycles through. Each enabled language has its
 * own layout and long-press accents. (Autocorrect dictionaries for the non-bundled languages download
 * on demand — wired in a later phase.)
 */
class LanguagesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        root.addView(label(getString(R.string.setup_languages), 28f, R.color.white))
        root.addView(label(getString(R.string.setup_languages_sub), 14f, R.color.gray))

        val enabled = Prefs.enabledLanguages(this).toMutableSet()
        for (l in Languages.ALL) {
            val toggle = LightToggle(this).apply {
                setText(l.name)
                setPadding(0, pad, 0, 0)
                isChecked = l.code in enabled
                setOnCheckedChangeListener { on ->
                    if (on) enabled.add(l.code) else enabled.remove(l.code)
                    Prefs.setEnabledLanguages(this@LanguagesActivity, enabled)
                }
            }
            root.addView(toggle)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }
}
