package app.lightphonekeyboard

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Keys & feel screen: how the keyboard feels and looks — haptics, sound, key timing, cursor swipe,
 * height, the number row and the language indicator. LightOS template style (see [LightUi]).
 */
class KeyboardSettingsActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LightUi.screen(this, getString(R.string.section_keys)) { c ->
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
        })
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
