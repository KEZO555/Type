package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup + settings, in the LightOS template style (see [LightUi]): a title header over big text — two
 * setup steps, then each option as a small label above its large current value (tap to change), with a
 * short grey hint beneath. Pure B/W, text-first. (On LightOS the system keyboard screens may be buried;
 * adb fallback: `adb shell ime enable app.lightphonekeyboard.debug/...LightImeService` then `ime set`.)
 */
class SetupActivity : AppCompatActivity() {

    private var voiceValue: TextView? = null
    private var voiceStatus: TextView? = null
    private var clearVoice: TextView? = null

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
            LightUi.navItem(c, getString(R.string.setup_emoji), getString(R.string.setup_emoji_sub)) {
                startActivity(Intent(this, EmojiSettingsActivity::class.java))
            }
            // Voice dictation — toggling on downloads the offline model once (async; status shown below).
            buildVoice(c)
            LightUi.hint(c, getString(R.string.setup_tip))
        })
        refreshVoice()
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

    /** Voice row (kept custom so the async download can update the value + status text in place). */
    private fun buildVoice(c: LinearLayout) {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val label = TextView(this).apply {
            text = getString(R.string.setup_voice); textSize = 15f; setTextColor(getColor(R.color.gray))
        }
        val value = TextView(this).apply {
            textSize = 26f; setTextColor(getColor(R.color.white)); text = if (Prefs.voiceEnabled(this@SetupActivity)) "On" else "Off"
        }.also { voiceValue = it }
        val sub = TextView(this).apply {
            text = getString(R.string.setup_voice_sub); textSize = 13f; setTextColor(getColor(R.color.gray)); setPadding(0, px(4f), 0, 0)
        }
        val status = TextView(this).apply {
            textSize = 13f; setTextColor(getColor(R.color.gray)); visibility = View.GONE
        }.also { voiceStatus = it }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundResource(rippleRes())
            setOnClickListener { onVoiceToggle(!Prefs.voiceEnabled(this@SetupActivity)) }
            addView(label); addView(value); addView(sub); addView(status)
        }
        c.addView(row, gap(px(26f)))
        clearVoice = TextView(this).apply {
            text = getString(R.string.setup_voice_clear); textSize = 20f; setTextColor(getColor(R.color.white))
            isClickable = true; setBackgroundResource(rippleRes()); visibility = View.GONE
            setOnClickListener { clearVoiceModel() }
        }
        c.addView(clearVoice, gap(px(20f)))
    }

    private fun gap(top: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = top }

    private fun rippleRes(): Int =
        android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId

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

    private fun setVoiceStatus(text: String?) {
        voiceStatus?.apply {
            this.text = text.orEmpty()
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** Show the "delete model" action only when the model is on disk; reflect the On/Off value. */
    private fun refreshVoice() {
        clearVoice?.visibility = if (VoiceModel.isInstalled(this)) View.VISIBLE else View.GONE
        voiceValue?.text = if (Prefs.voiceEnabled(this)) "On" else "Off"
    }

    private fun onVoiceToggle(on: Boolean) {
        if (!on) {
            Prefs.setVoiceEnabled(this, false)
            setVoiceStatus(null); refreshVoice()
            return
        }
        if (VoiceModel.isInstalled(this)) {
            Prefs.setVoiceEnabled(this, true)
            setVoiceStatus("Voice ready."); refreshVoice()
            return
        }
        // Download the model first; only enable on success.
        setVoiceStatus("Downloading voice model…")
        VoiceModel.install(
            this,
            onProgress = { p -> setVoiceStatus("Downloading voice model… $p%") },
            onDone = { Prefs.setVoiceEnabled(this, true); setVoiceStatus("Voice ready."); refreshVoice() },
            onError = { msg -> Prefs.setVoiceEnabled(this, false); setVoiceStatus("Download failed: $msg"); refreshVoice() },
        )
    }

    /** Delete the downloaded model to reclaim space; voice turns off until re-downloaded. */
    private fun clearVoiceModel() {
        VoiceModel.remove(this)
        Prefs.setVoiceEnabled(this, false)
        setVoiceStatus("Voice model deleted."); refreshVoice()
    }
}
