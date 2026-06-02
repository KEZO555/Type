package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal two-step setup: enable the keyboard in system settings, then pick it. Pure B/W, text-first,
 * matching the Light ethos. (On LightOS these system screens may be buried; adb fallback:
 * `adb shell ime enable app.lightphonekeyboard.debug/app.lightphonekeyboard.LightImeService` then
 * `ime set ...`.)
 */
class SetupActivity : AppCompatActivity() {

    private var voiceToggle: LightToggle? = null
    private var voiceStatus: TextView? = null
    private var clearVoiceBtn: Button? = null

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

        fun action(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 20f
            setTextColor(getColor(R.color.white))
            setBackgroundColor(getColor(R.color.black))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

        // A setup step, styled as a tappable row: label on the left, a subtle right arrow on the right.
        fun stepRow(text: String, onClick: () -> Unit): View {
            val ripple = TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }
            val labelView = label(text, 20f, R.color.white)
            val arrowView = ImageView(this).apply { setImageResource(R.drawable.ic_setup_arrow) }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { onClick() }
                addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(arrowView)
            }
        }

        // Light Phone-style toggle row (see LightToggle): a line-and-dot mark with the label beside it.
        fun toggle(textRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) = LightToggle(this).apply {
            setText(getString(textRes))
            setPadding(0, pad, 0, 0)
            isChecked = checked
            setOnCheckedChangeListener(onChange)
        }

        // Build the pieces once, then arrange them for the current orientation.
        val titleView = label(getString(R.string.setup_title), 28f, R.color.white)
        val blurbView = label(getString(R.string.setup_blurb), 16f, R.color.gray)
        val step1 = stepRow(getString(R.string.setup_step1)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        val step2 = stepRow(getString(R.string.setup_step2)) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        val doneView = label(getString(R.string.setup_done), 14f, R.color.gray)

        val autocorrectToggle = toggle(R.string.setup_autocorrect, Prefs.autocorrect(this)) {
            Prefs.setAutocorrect(this, it)
        }
        val autocorrectSub = label(getString(R.string.setup_autocorrect_sub), 14f, R.color.gray)
        val autoCapToggle = toggle(R.string.setup_auto_cap, Prefs.autoCap(this)) {
            Prefs.setAutoCap(this, it)
        }
        val autoCapSub = label(getString(R.string.setup_auto_cap_sub), 14f, R.color.gray)
        val doubleSpaceToggle = toggle(R.string.setup_double_space, Prefs.doubleSpacePeriod(this)) {
            Prefs.setDoubleSpacePeriod(this, it)
        }
        val doubleSpaceSub = label(getString(R.string.setup_double_space_sub), 14f, R.color.gray)
        // Haptics: a row that cycles Off → Light → Medium → Strong and previews the chosen strength.
        val hapticNames = listOf("Off", "Light", "Medium", "Strong")
        fun hapticText() = "Haptic feedback: ${hapticNames[Prefs.hapticLevel(this).coerceIn(0, 3)]}"
        val hapticBtn = action(hapticText()) { }
        hapticBtn.setPadding(0, pad, 0, 0)
        hapticBtn.setOnClickListener {
            val next = (Prefs.hapticLevel(this) + 1) % hapticNames.size
            Prefs.setHapticLevel(this, next)
            hapticBtn.text = hapticText()
            previewHaptic(next)
        }
        val hapticSub = label(getString(R.string.setup_haptic_sub), 14f, R.color.gray)
        // Key-press sound.
        val soundToggle = toggle(R.string.setup_sound, Prefs.soundEnabled(this)) {
            Prefs.setSoundEnabled(this, it)
        }
        val soundSub = label(getString(R.string.setup_sound_sub), 14f, R.color.gray)
        // A row that cycles a three-step level (Low/Normal/High style) and persists it.
        fun stepper(prefix: String, names: List<String>, get: () -> Int, set: (Int) -> Unit): Button {
            val btn = action("") { }
            btn.setPadding(0, pad, 0, 0)
            fun render() { btn.text = "$prefix: ${names[get().coerceIn(0, names.size - 1)]}" }
            btn.setOnClickListener { set((get() + 1) % names.size); render() }
            render()
            return btn
        }
        // Names are indexed by the stored level value (0 = LEVEL_LOW, 1 = NORMAL, 2 = HIGH).
        val lpDelayBtn = stepper("Long-press delay", listOf("Slow", "Normal", "Fast"),
            { Prefs.longPressDelay(this) }, { Prefs.setLongPressDelay(this, it) })
        val lpDelaySub = label(getString(R.string.setup_lp_delay_sub), 14f, R.color.gray)
        val swipeBtn = stepper("Cursor swipe", listOf("Low", "Normal", "High"),
            { Prefs.swipeSensitivity(this) }, { Prefs.setSwipeSensitivity(this, it) })
        val swipeSub = label(getString(R.string.setup_swipe_sub), 14f, R.color.gray)
        val kbHeightBtn = stepper("Keyboard height", listOf("Compact", "Normal", "Tall"),
            { Prefs.keyboardHeight(this) }, { Prefs.setKeyboardHeight(this, it) })
        val kbHeightSub = label(getString(R.string.setup_kb_height_sub), 14f, R.color.gray)
        val numberRowToggle = toggle(R.string.setup_number_row, Prefs.numberRow(this)) {
            Prefs.setNumberRow(this, it)
        }
        val numberRowSub = label(getString(R.string.setup_number_row_sub), 14f, R.color.gray)
        // Emoji set — opens its own picker screen.
        val emojiRow = stepRow(getString(R.string.setup_emoji)) {
            startActivity(Intent(this, EmojiSettingsActivity::class.java))
        }
        val emojiSub = label(getString(R.string.setup_emoji_sub), 14f, R.color.gray)
        val tipView = label(getString(R.string.setup_tip), 14f, R.color.gray)
        // Languages: informational — the globe key on the keyboard cycles English → Hebrew → emoji.
        val languagesView = label(getString(R.string.setup_languages), 20f, R.color.white)
        val languagesSub = label(getString(R.string.setup_languages_sub), 14f, R.color.gray)
        // Voice dictation — turning it on downloads the offline model once.
        voiceStatus = label("", 14f, R.color.gray)
        voiceToggle = toggle(R.string.setup_voice, Prefs.voiceEnabled(this)) { onVoiceToggle(it) }
        val voiceSub = label(getString(R.string.setup_voice_sub), 14f, R.color.gray)
        clearVoiceBtn = action(getString(R.string.setup_voice_clear)) { clearVoice() }

        listOf(
            titleView, blurbView, step1, step2, doneView,
            languagesView, languagesSub,
            autocorrectToggle, autocorrectSub,
            autoCapToggle, autoCapSub,
            doubleSpaceToggle, doubleSpaceSub,
            hapticBtn, hapticSub,
            soundToggle, soundSub,
            lpDelayBtn, lpDelaySub,
            swipeBtn, swipeSub,
            kbHeightBtn, kbHeightSub,
            numberRowToggle, numberRowSub,
            emojiRow, emojiSub,
            voiceToggle!!, voiceSub, voiceStatus!!, clearVoiceBtn!!,
            tipView,
        ).forEach { root.addView(it) }
        refreshVoice()

        // Scrollable: in portrait the setup content is taller than the Light Phone screen.
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    /** Buzz once at the chosen strength so the user feels what they picked (matches the keyboard). */
    private fun previewHaptic(level: Int) {
        val (ms, amp) = when (level) {
            Prefs.HAPTIC_LIGHT -> 18L to 130
            Prefs.HAPTIC_MEDIUM -> 30L to 200
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

    /** Show the "clear download" action only when the model is actually on disk. */
    private fun refreshVoice() {
        clearVoiceBtn?.visibility = if (VoiceModel.isInstalled(this)) View.VISIBLE else View.GONE
    }

    private fun onVoiceToggle(on: Boolean) {
        if (!on) {
            Prefs.setVoiceEnabled(this, false)
            voiceStatus?.text = ""
            return
        }
        if (VoiceModel.isInstalled(this)) {
            Prefs.setVoiceEnabled(this, true)
            voiceStatus?.text = "Voice ready."
            return
        }
        // Download the model first; only enable on success.
        voiceToggle?.isEnabled = false
        voiceStatus?.text = "Downloading voice model…"
        VoiceModel.install(
            this,
            onProgress = { p -> voiceStatus?.text = "Downloading voice model… $p%" },
            onDone = {
                voiceToggle?.isEnabled = true
                Prefs.setVoiceEnabled(this, true)
                voiceStatus?.text = "Voice ready."
                refreshVoice()
            },
            onError = { msg ->
                voiceToggle?.isEnabled = true
                Prefs.setVoiceEnabled(this, false)
                voiceToggle?.isChecked = false
                voiceStatus?.text = "Download failed: $msg"
                refreshVoice()
            },
        )
    }

    /** Delete the downloaded model to reclaim space; voice turns off until re-downloaded. */
    private fun clearVoice() {
        VoiceModel.remove(this)
        Prefs.setVoiceEnabled(this, false)
        voiceToggle?.isChecked = false
        voiceStatus?.text = "Voice model deleted."
        refreshVoice()
    }
}
