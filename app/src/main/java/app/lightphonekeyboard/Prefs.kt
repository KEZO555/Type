package app.lightphonekeyboard

import android.content.Context

/** Tiny SharedPreferences wrapper. Single-process app, so the Activity's writes are seen by the IME. */
object Prefs {
    private const val FILE = "light_keyboard_prefs"
    private const val KEY_AUTOCORRECT = "autocorrect"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_NUMBER_ROW = "number_row"
    private const val KEY_PREDICTION = "prediction"
    private const val KEY_HAPTIC = "haptic_level"
    private const val KEY_AUTO_CAP = "auto_cap"
    private const val KEY_DOUBLE_SPACE = "double_space_period"
    private const val KEY_RECENT_EMOJI = "recent_emoji"
    private const val RECENT_EMOJI_MAX = 12

    /** Haptic strength levels. */
    const val HAPTIC_OFF = 0
    const val HAPTIC_LIGHT = 1
    const val HAPTIC_MEDIUM = 2
    const val HAPTIC_STRONG = 3

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Word-level autocorrect using the device's spell checker. On by default. */
    fun autocorrect(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTOCORRECT, true)

    fun setAutocorrect(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTOCORRECT, value).apply()

    /** Voice dictation (mic key + offline STT). Off by default; turning it on downloads the model. */
    fun voiceEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_VOICE, false)

    fun setVoiceEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_VOICE, value).apply()

    /** Persistent number row above the letters. Off by default. */
    fun numberRow(c: Context): Boolean = prefs(c).getBoolean(KEY_NUMBER_ROW, false)

    fun setNumberRow(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_NUMBER_ROW, value).apply()

    /** Word-prediction suggestions strip. On by default. */
    fun prediction(c: Context): Boolean = prefs(c).getBoolean(KEY_PREDICTION, true)

    fun setPrediction(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_PREDICTION, value).apply()

    /** Key/cursor haptic strength (HAPTIC_OFF..HAPTIC_STRONG). Strong by default. */
    fun hapticLevel(c: Context): Int = prefs(c).getInt(KEY_HAPTIC, HAPTIC_STRONG)

    fun setHapticLevel(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_HAPTIC, value).apply()

    /** Auto-capitalize at the start of sentences. On by default. */
    fun autoCap(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_CAP, true)

    fun setAutoCap(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTO_CAP, value).apply()

    /** Double-tap the space bar to insert ". ". On by default. */
    fun doubleSpacePeriod(c: Context): Boolean = prefs(c).getBoolean(KEY_DOUBLE_SPACE, true)

    fun setDoubleSpacePeriod(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_DOUBLE_SPACE, value).apply()

    /** Most-recently-used emoji, newest first (newline-separated). Drives the emoji grid order. */
    fun recentEmoji(c: Context): List<String> =
        prefs(c).getString(KEY_RECENT_EMOJI, "")!!.split('\n').filter { it.isNotEmpty() }

    /** Push [emoji] to the front of the recents list (deduped, capped). */
    fun pushRecentEmoji(c: Context, emoji: String) {
        val list = ArrayList<String>(recentEmoji(c))
        list.remove(emoji)
        list.add(0, emoji)
        while (list.size > RECENT_EMOJI_MAX) list.removeAt(list.size - 1)
        prefs(c).edit().putString(KEY_RECENT_EMOJI, list.joinToString("\n")).apply()
    }
}
