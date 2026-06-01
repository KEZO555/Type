package app.lightphonekeyboard

import android.content.Context

/** Tiny SharedPreferences wrapper. Single-process app, so the Activity's writes are seen by the IME. */
object Prefs {
    private const val FILE = "light_keyboard_prefs"
    private const val KEY_AUTOCORRECT = "autocorrect"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_NUMBER_ROW = "number_row"
    private const val KEY_PREDICTION = "prediction"
    private const val KEY_RECENT_EMOJI = "recent_emoji"
    private const val RECENT_EMOJI_MAX = 12

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
