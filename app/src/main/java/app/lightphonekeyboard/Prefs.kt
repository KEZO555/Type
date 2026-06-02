package app.lightphonekeyboard

import android.content.Context

/** Tiny SharedPreferences wrapper. Single-process app, so the Activity's writes are seen by the IME. */
object Prefs {
    private const val FILE = "light_keyboard_prefs"
    private const val KEY_AUTOCORRECT = "autocorrect"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_NUMBER_ROW = "number_row"
    private const val KEY_HAPTIC = "haptic_level"
    private const val KEY_AUTO_CAP = "auto_cap"
    private const val KEY_DOUBLE_SPACE = "double_space_period"
    private const val KEY_RECENT_EMOJI = "recent_emoji"
    private const val RECENT_EMOJI_MAX = 12
    private const val KEY_EMOJI_SET = "emoji_set"
    private const val KEY_ENABLED_LANGS = "enabled_languages"
    private const val KEY_ACTIVE_LANG = "active_language"
    private const val KEY_SOUND = "key_sound"
    private const val KEY_LP_DELAY = "longpress_delay"
    private const val KEY_SWIPE_SENS = "swipe_sensitivity"
    private const val KEY_KB_HEIGHT = "keyboard_height"

    /** Haptic strength levels. */
    const val HAPTIC_OFF = 0
    const val HAPTIC_LIGHT = 1
    const val HAPTIC_MEDIUM = 2
    const val HAPTIC_STRONG = 3

    /** Three-step levels shared by long-press delay, swipe sensitivity, and keyboard height. */
    const val LEVEL_LOW = 0
    const val LEVEL_NORMAL = 1
    const val LEVEL_HIGH = 2

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

    /** Play a click on each key press (uses the system key-press sound). Off by default. */
    fun soundEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_SOUND, false)

    fun setSoundEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_SOUND, value).apply()

    /** How long to hold a key before its long-press fires (LEVEL_LOW = slow … LEVEL_HIGH = fast). */
    fun longPressDelay(c: Context): Int = prefs(c).getInt(KEY_LP_DELAY, LEVEL_NORMAL)

    fun setLongPressDelay(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_LP_DELAY, value).apply()

    /** Space-bar cursor swipe sensitivity (LEVEL_HIGH = the caret moves with less finger travel). */
    fun swipeSensitivity(c: Context): Int = prefs(c).getInt(KEY_SWIPE_SENS, LEVEL_NORMAL)

    fun setSwipeSensitivity(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_SWIPE_SENS, value).apply()

    /** Keyboard height (LEVEL_LOW = compact … LEVEL_HIGH = tall). */
    fun keyboardHeight(c: Context): Int = prefs(c).getInt(KEY_KB_HEIGHT, LEVEL_NORMAL)

    fun setKeyboardHeight(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_KB_HEIGHT, value).apply()

    /** Languages enabled in the globe rotation (comma-separated ISO codes). English + Hebrew by
     *  default, preserving the original behaviour. */
    fun enabledLanguages(c: Context): Set<String> =
        prefs(c).getString(KEY_ENABLED_LANGS, "en,he")!!.split(',').filter { it.isNotEmpty() }.toSet()

    fun setEnabledLanguages(c: Context, codes: Collection<String>) =
        prefs(c).edit().putString(KEY_ENABLED_LANGS, codes.joinToString(",")).apply()

    /** The language the keyboard last showed, so it reopens in the same one. */
    fun activeLanguage(c: Context): String = prefs(c).getString(KEY_ACTIVE_LANG, "en")!!

    fun setActiveLanguage(c: Context, code: String) =
        prefs(c).edit().putString(KEY_ACTIVE_LANG, code).apply()

    /** The user's chosen emoji set (newline-separated), or empty if they haven't customized it — in
     *  which case the keyboard falls back to its default set. */
    fun emojiSet(c: Context): List<String> =
        prefs(c).getString(KEY_EMOJI_SET, "")!!.split('\n').filter { it.isNotEmpty() }

    fun setEmojiSet(c: Context, list: List<String>) =
        prefs(c).edit().putString(KEY_EMOJI_SET, list.joinToString("\n")).apply()

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
