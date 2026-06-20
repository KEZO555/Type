package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup + settings, in the LightOS template style (see [LightUi]): a title header over big text — two
 * setup steps, then each option as a small label above its large current value (tap to change), with a
 * short grey hint beneath. Pure B/W, text-first. (On LightOS the system keyboard screens may be buried;
 * adb fallback: `adb shell ime enable app.lightphonekeyboard.debug/...LightImeService` then `ime set`.)
 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LightUi.screen(this, getString(R.string.setup_title), showBack = false) { c ->
            LightUi.hint(c, getString(R.string.setup_blurb))

            // Two setup steps.
            LightUi.navItem(c, getString(R.string.setup_step1)) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            LightUi.navItem(c, getString(R.string.setup_step2)) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            LightUi.hint(c, getString(R.string.setup_done))

            // Languages.
            LightUi.navItem(c, getString(R.string.setup_languages), getString(R.string.setup_languages_sub)) {
                startActivity(Intent(this, LanguagesActivity::class.java))
            }
            // Autocorrect + suggestion bar.
            toggleItem(c, R.string.setup_autocorrect, R.string.setup_autocorrect_sub,
                { Prefs.autocorrect(this) }, { Prefs.setAutocorrect(this, it) })
            toggleItem(c, R.string.setup_gesture, R.string.setup_gesture_sub,
                { Prefs.gestureTyping(this) }, { Prefs.setGestureTyping(this, it) })
            toggleItem(c, R.string.setup_suggestions, R.string.setup_suggestions_sub,
                { Prefs.suggestions(this) }, { Prefs.setSuggestions(this, it) })
            LightUi.navItem(c, getString(R.string.setup_vocab), getString(R.string.setup_vocab_sub)) {
                startActivity(Intent(this, VocabularyActivity::class.java))
            }
            // Typing behaviour.
            toggleItem(c, R.string.setup_auto_cap, R.string.setup_auto_cap_sub,
                { Prefs.autoCap(this) }, { Prefs.setAutoCap(this, it) })
            toggleItem(c, R.string.setup_double_space, R.string.setup_double_space_sub,
                { Prefs.doubleSpacePeriod(this) }, { Prefs.setDoubleSpacePeriod(this, it) })
            // Haptics — cycles Off/Light/Medium/Strong and previews the chosen strength.
            cycleItem(c, "Haptic feedback", R.string.setup_haptic_sub, listOf("Off", "Light", "Medium", "Strong"),
                { Prefs.hapticLevel(this) }, { Prefs.setHapticLevel(this, it); previewHaptic(it) })
            toggleItem(c, R.string.setup_sound, R.string.setup_sound_sub,
                { Prefs.soundEnabled(this) }, { Prefs.setSoundEnabled(this, it) })
            cycleItem(c, "Long-press delay", R.string.setup_lp_delay_sub, listOf("Slow", "Normal", "Fast"),
                { Prefs.longPressDelay(this) }, { Prefs.setLongPressDelay(this, it) })
            cycleItem(c, "Cursor swipe", R.string.setup_swipe_sub, listOf("Low", "Normal", "High"),
                { Prefs.swipeSensitivity(this) }, { Prefs.setSwipeSensitivity(this, it) })
            cycleItem(c, "Keyboard height", R.string.setup_kb_height_sub, listOf("Compact", "Normal", "Tall"),
                { Prefs.keyboardHeight(this) }, { Prefs.setKeyboardHeight(this, it) })
            toggleItem(c, R.string.setup_number_row, R.string.setup_number_row_sub,
                { Prefs.numberRow(this) }, { Prefs.setNumberRow(this, it) })
            toggleItem(c, R.string.setup_lang_indicator, R.string.setup_lang_indicator_sub,
                { Prefs.languageIndicator(this) }, { Prefs.setLanguageIndicator(this, it) })
            LightUi.navItem(c, getString(R.string.setup_emoji), getString(R.string.setup_emoji_sub)) {
                startActivity(Intent(this, EmojiSettingsActivity::class.java))
            }
            // Voice dictation — master on/off. Each language's offline model is downloaded per-language
            // in Settings → Languages (Hebrew uses the phone's recognizer, if it has one).
            toggleItem(c, R.string.setup_voice, R.string.setup_voice_sub,
                { Prefs.voiceEnabled(this) }, { Prefs.setVoiceEnabled(this, it) })
            LightUi.hint(c, getString(R.string.setup_tip))
        })
    }

    /** A boolean setting as "label / On|Off". */
    private fun toggleItem(c: LinearLayout, labelRes: Int, subRes: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        LightUi.valueItem(c, getString(labelRes), getString(subRes),
            value = { if (get()) "On" else "Off" }, onClick = { set(!get()) })
    }

    /** A multi-step setting as "label / <current name>", cycling on tap. */
    private fun cycleItem(c: LinearLayout, label: String, subRes: Int, names: List<String>, get: () -> Int, set: (Int) -> Unit) {
        LightUi.valueItem(c, label, getString(subRes),
            value = { names[get().coerceIn(0, names.size - 1)] }, onClick = { set((get() + 1) % names.size) })
    }

    /** Buzz once at the chosen strength so the user feels what they picked (matches the keyboard). */
    private fun previewHaptic(level: Int) {
        val (ms, amp) = when (level) {
            Prefs.HAPTIC_LIGHT -> 30L to 200
            Prefs.HAPTIC_MEDIUM -> 38L to 228
            Prefs.HAPTIC_STRONG -> 45L to 255
            else -> return
        }
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        }
        if (v == null || !v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, amp)) }
    }

}
