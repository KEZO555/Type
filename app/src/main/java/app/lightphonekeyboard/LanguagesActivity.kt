package app.lightphonekeyboard

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Languages screen: toggle which languages the globe key cycles through (a per-language On/Off). Only
 * English ships an autocorrect dictionary inside the APK; every other language **downloads** its
 * dictionary — automatically the moment you turn it on, so the base app stays small. A line beneath each
 * shows that download (and lets you delete it). Every language but Hebrew can also download an offline
 * voice-dictation model here (when voice is enabled). LightOS template style (see [LightUi]).
 */
class LanguagesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val enabled = Prefs.enabledLanguages(this).toMutableSet()
        setContentView(LightUi.screen(this, getString(R.string.setup_languages)) { content ->
            LightUi.hint(content, getString(R.string.setup_languages_sub))
            for (l in Languages.ALL) {
                val dict = if (l.dictUrl != null) dictLine(l) else null
                LightUi.valueItem(
                    content,
                    label = l.name,
                    value = { if (l.code in enabled) "On" else "Off" },
                    onClick = {
                        val turningOn = l.code !in enabled
                        if (turningOn) enabled.add(l.code) else enabled.remove(l.code)
                        Prefs.setEnabledLanguages(this, enabled)
                        // Choosing a language fetches its dictionary so it works offline afterwards.
                        if (turningOn && dict != null && !DictModel.isInstalled(this, l.code)) downloadDict(dict, l)
                    },
                )
                if (dict != null) content.addView(dict)
                // Offline voice model — shown only while voice is enabled (the master toggle is in Setup).
                if (l.voiceUrl != null && Prefs.voiceEnabled(this)) content.addView(voiceLine(l))
            }
        })
    }

    /** Small tappable line under a language: download / progress / installed-tap-to-delete its voice model. */
    private fun voiceLine(l: LangDef): TextView {
        val d = resources.displayMetrics.density
        val view = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.gray))
            setPadding(0, (4 * d).toInt(), 0, 0)
            isClickable = true
        }
        renderVoice(view, l)
        return view
    }

    private fun renderVoice(view: TextView, l: LangDef) {
        if (VoiceModel.isInstalled(this, l.code)) {
            view.text = getString(R.string.voice_installed)
            view.setOnClickListener { VoiceModel.remove(this, l.code); renderVoice(view, l) }
        } else {
            view.text = getString(R.string.voice_download, l.voiceSizeMb)
            view.setOnClickListener { downloadVoice(view, l) }
        }
    }

    private fun downloadVoice(view: TextView, l: LangDef) {
        view.setOnClickListener(null)
        view.text = getString(R.string.voice_downloading, 0)
        VoiceModel.install(
            this, l.code, l.voiceUrl!!,
            onProgress = { p -> view.text = getString(R.string.voice_downloading, p) },
            onDone = { renderVoice(view, l) },
            onError = { msg ->
                view.text = getString(R.string.voice_failed, msg)
                view.setOnClickListener { downloadVoice(view, l) }
            },
        )
    }

    /** Small tappable line under a downloadable language: download / progress / installed-tap-to-delete. */
    private fun dictLine(l: LangDef): TextView {
        val d = resources.displayMetrics.density
        val view = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.gray))
            setPadding(0, (4 * d).toInt(), 0, 0)
            isClickable = true
        }
        renderDict(view, l)
        return view
    }

    private fun renderDict(view: TextView, l: LangDef) {
        if (DictModel.isInstalled(this, l.code)) {
            view.text = getString(R.string.dict_installed)
            view.setOnClickListener { DictModel.remove(this, l.code); renderDict(view, l) }
        } else {
            view.text = getString(R.string.dict_download)
            view.setOnClickListener { downloadDict(view, l) }
        }
    }

    private fun downloadDict(view: TextView, l: LangDef) {
        view.setOnClickListener(null)
        view.text = getString(R.string.dict_downloading, 0)
        DictModel.install(
            this, l,
            onProgress = { p -> view.text = getString(R.string.dict_downloading, p) },
            onDone = { renderDict(view, l) },
            onError = { msg ->
                view.text = getString(R.string.dict_failed, msg)
                view.setOnClickListener { downloadDict(view, l) }
            },
        )
    }
}
