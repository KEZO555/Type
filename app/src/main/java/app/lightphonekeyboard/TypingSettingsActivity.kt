package app.lightphonekeyboard

import android.content.Intent
import android.os.Bundle

/**
 * Typing screen: everything about what gets typed — autocorrect, suggestions, gesture typing,
 * learned words, capitalization habits and voice dictation. LightOS template style (see [LightUi]).
 */
class TypingSettingsActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LightUi.screen(this, getString(R.string.section_typing)) { c ->
            toggleItem(c, R.string.setup_autocorrect, R.string.setup_autocorrect_sub,
                { Prefs.autocorrect(this) }, { Prefs.setAutocorrect(this, it) })
            toggleItem(c, R.string.setup_gesture, R.string.setup_gesture_sub,
                { Prefs.gestureTyping(this) }, { Prefs.setGestureTyping(this, it) })
            toggleItem(c, R.string.setup_suggestions, R.string.setup_suggestions_sub,
                { Prefs.suggestions(this) }, { Prefs.setSuggestions(this, it) })
            LightUi.navItem(c, getString(R.string.setup_vocab), getString(R.string.setup_vocab_sub)) {
                startActivity(Intent(this, VocabularyActivity::class.java))
            }
            toggleItem(c, R.string.setup_auto_cap, R.string.setup_auto_cap_sub,
                { Prefs.autoCap(this) }, { Prefs.setAutoCap(this, it) })
            toggleItem(c, R.string.setup_double_space, R.string.setup_double_space_sub,
                { Prefs.doubleSpacePeriod(this) }, { Prefs.setDoubleSpacePeriod(this, it) })
            // Voice dictation — master on/off. Each language's offline model is downloaded
            // per-language in Settings → Languages.
            toggleItem(c, R.string.setup_voice, R.string.setup_voice_sub,
                { Prefs.voiceEnabled(this) }, { Prefs.setVoiceEnabled(this, it) })
        })
    }
}
