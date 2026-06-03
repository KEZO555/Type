package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Languages screen: toggle which languages the globe key cycles through. Each enabled language has its
 * own layout and long-press accents. English and Hebrew include offline autocorrect in the APK; the
 * other languages offer an autocorrect dictionary that downloads on demand (a small row under each).
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
            if (l.dictUrl != null) root.addView(dictButton(l))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    /** The download / delete control for one downloadable-dictionary language. Reflects its state and
     *  drives [DictModel] when tapped. */
    private fun dictButton(l: LangDef): Button {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val btn = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(getColor(R.color.gray))
            setBackgroundColor(getColor(R.color.black))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, pad / 4, 0, 0)
        }
        renderDict(btn, l)
        return btn
    }

    /** Idle state: installed → tap to delete; not installed → tap to download. */
    private fun renderDict(btn: Button, l: LangDef) {
        btn.isEnabled = true
        if (DictModel.isInstalled(this, l.code)) {
            btn.text = getString(R.string.dict_installed)
            btn.setOnClickListener {
                DictModel.remove(this, l.code)
                renderDict(btn, l)
            }
        } else {
            btn.text = getString(R.string.dict_download)
            btn.setOnClickListener { downloadDict(btn, l) }
        }
    }

    private fun downloadDict(btn: Button, l: LangDef) {
        btn.isEnabled = false
        btn.setOnClickListener(null)
        btn.text = getString(R.string.dict_downloading, 0)
        DictModel.install(
            this, l,
            onProgress = { p -> btn.text = getString(R.string.dict_downloading, p) },
            onDone = { renderDict(btn, l) },
            onError = { msg ->
                btn.isEnabled = true
                btn.text = getString(R.string.dict_failed, msg)
                btn.setOnClickListener { downloadDict(btn, l) }
            },
        )
    }
}
