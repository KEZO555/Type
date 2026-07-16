package app.lightphonekeyboard

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * The colour-filter half of the app (ported from the standalone Lightson utility). The phone stays
 * in grayscale; this service only pauses it per app:
 *
 *  - Colour apps (chosen in Settings) get full colour while in the foreground; grayscale returns
 *    the moment they're left.
 *  - A hardware keymap (e.g. long-pressing the LP3's camera button) gives the *current* app colour
 *    until it's left — inactive on the home screen, where keys keep their stock behaviour.
 *  - Optionally, locking the phone closes every app used since the last lock and restores
 *    grayscale immediately.
 */
class ColorFilterService : AccessibilityService() {

    private var volumeUpHeld = false
    private var volumeDownHeld = false
    private var lastVolumeUpPress = 0L
    private var lastVolumeDownPress = 0L
    private var lastToggle = 0L

    private var foregroundPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cameraLongPressFired = false
    private val cameraLongPressRunnable = Runnable {
        cameraLongPressFired = true
        fireGesture(Prefs.COLOR_KEYMAP_CAMERA)
    }

    /** Packages that handle the camera intent — the shutter key is theirs. */
    private val cameraPackages: Set<String> by lazy {
        packageManager
            .queryIntentActivities(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /** Apps the user has opened since the last lock, killed on screen off. */
    private val sessionApps = mutableSetOf<String>()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenLocked()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (shouldIgnore(pkg)) return
        foregroundPackage = pkg
        if (!isHomePackage(pkg)) sessionApps.add(pkg)

        if (pkg in Prefs.colorApps(this)) {
            if (ColorFilter.isGrayscale(this) && ColorFilter.setGrayscale(this, false)) {
                Prefs.setWeDisabledFilter(this, true)
            }
        } else if (Prefs.weDisabledFilter(this)) {
            if (ColorFilter.setGrayscale(this, true)) {
                Prefs.setWeDisabledFilter(this, false)
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (KeyEventLog.active) {
            val action = when (event.action) {
                KeyEvent.ACTION_DOWN -> "down"
                KeyEvent.ACTION_UP -> "up"
                else -> "action ${event.action}"
            }
            KeyEventLog.push("${KeyEvent.keyCodeToString(event.keyCode)}  $action  repeat=${event.repeatCount}")
        }

        val colorMap = Prefs.colorKeymap(this)
        val recentsMap = Prefs.recentsKeymap(this)
        val wheelBrightness = Prefs.wheelBrightness(this)
        if (colorMap == Prefs.COLOR_KEYMAP_NONE && recentsMap == Prefs.COLOR_KEYMAP_NONE && !wheelBrightness) {
            return false
        }
        // Keymaps only apply inside apps — on the home screen (LightOS) the keys keep their stock
        // behaviour. Unknown foreground (e.g. right after a service restart) counts as home.
        if (isHomeForeground()) return false
        val code = event.keyCode

        if (code == KeyEvent.KEYCODE_CAMERA) {
            val cameraBound = colorMap == Prefs.COLOR_KEYMAP_CAMERA || recentsMap == Prefs.COLOR_KEYMAP_CAMERA
            if (!cameraBound) return false
            return onCameraKey(event)
        }
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    val now = event.eventTime
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) volumeUpHeld = true else volumeDownHeld = true

                    if (volumeUpHeld && volumeDownHeld && fireGesture(Prefs.COLOR_KEYMAP_VOLUME_CHORD)) {
                        // Swallow the completing key so it doesn't also change the volume.
                        return true
                    }
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                        if (now - lastVolumeUpPress < DOUBLE_PRESS_WINDOW_MS) {
                            fireGesture(Prefs.COLOR_KEYMAP_DOUBLE_VOLUME_UP)
                        }
                        lastVolumeUpPress = now
                    } else {
                        if (now - lastVolumeDownPress < DOUBLE_PRESS_WINDOW_MS) {
                            fireGesture(Prefs.COLOR_KEYMAP_DOUBLE_VOLUME_DOWN)
                        }
                        lastVolumeDownPress = now
                    }
                }
                // The LP3's side wheel arrives as volume keys: inside apps, turn it into brightness
                // steps (repeats included, so rolling the wheel keeps stepping) and swallow the key.
                if (wheelBrightness) {
                    adjustBrightness(if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) volumeUpHeld = false else volumeDownHeld = false
                if (wheelBrightness) return true
            }
        }
        return false
    }

    /** One wheel click = one brightness step. Needs the user-grantable "Modify system settings". */
    private fun adjustBrightness(direction: Int) {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, R.string.wheel_toast_no_permission, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = (cur + direction * BRIGHTNESS_STEP).coerceIn(BRIGHTNESS_MIN, 255)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        }
    }

    /**
     * Runs whichever action [gesture] is bound to (colour toggle wins if both settings share the
     * same gesture). Returns true if an action ran.
     */
    private fun fireGesture(gesture: Int): Boolean {
        when (gesture) {
            Prefs.colorKeymap(this) -> toggleFilter()
            Prefs.recentsKeymap(this) -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> return false
        }
        return true
    }

    /**
     * Long-press runs the bound action (colour toggle or recents); a short press keeps its usual
     * meaning by launching the camera ourselves, since a consumed key can't be re-injected. While a
     * camera app is in the foreground the key is left alone entirely so the shutter keeps working.
     */
    private fun onCameraKey(event: KeyEvent): Boolean {
        if (foregroundPackage in cameraPackages) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    cameraLongPressFired = false
                    handler.postDelayed(cameraLongPressRunnable, LONG_PRESS_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(cameraLongPressRunnable)
                if (!cameraLongPressFired) launchCamera()
                cameraLongPressFired = false
            }
        }
        return true
    }

    private fun launchCamera() {
        try {
            startActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            // No camera app — nothing sensible to do with a short press.
        }
    }

    private fun toggleFilter() {
        val now = System.currentTimeMillis()
        if (now - lastToggle < TOGGLE_DEBOUNCE_MS) return
        lastToggle = now

        val grayscale = !ColorFilter.isGrayscale(this)
        if (ColorFilter.setGrayscale(this, grayscale)) {
            // Colour is temporary: marking weDisabledFilter makes the window watcher restore
            // grayscale as soon as the user leaves the current app.
            Prefs.setWeDisabledFilter(this, !grayscale)
            Toast.makeText(
                this,
                if (grayscale) R.string.color_toast_gray else R.string.color_toast_color,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, R.string.color_toast_no_permission, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isHomeForeground(): Boolean {
        val fg = foregroundPackage ?: return true
        return isHomePackage(fg)
    }

    private fun isHomePackage(pkg: String): Boolean {
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        return pkg == home
    }

    /**
     * The screen locked: restore grayscale right away, and (when enabled) close every app used
     * since the previous lock. killBackgroundProcesses needs the target to actually be in the
     * background, so the kill runs after a short delay to let the foreground app settle. (Works on
     * the LP3's pre-14 Android; from Android 14 the OS restricts it to the caller's own app.)
     */
    private fun onScreenLocked() {
        if (Prefs.weDisabledFilter(this) && ColorFilter.setGrayscale(this, true)) {
            Prefs.setWeDisabledFilter(this, false)
        }

        if (!Prefs.closeAppsOnLock(this)) return
        val toKill = sessionApps.toList()
        sessionApps.clear()
        if (toKill.isEmpty()) return
        handler.postDelayed({
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            toKill.forEach { pkg -> runCatching { am.killBackgroundProcesses(pkg) } }
        }, KILL_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    /**
     * Transient windows fire window-state events on top of the real foreground app; reacting to
     * them would flicker the filter mid-use. Our own windows (settings, this keyboard) must not
     * count as "left the app" either — that would instantly undo a manual colour toggle.
     */
    private fun shouldIgnore(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg == "com.android.systemui") return true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    companion object {
        private const val DOUBLE_PRESS_WINDOW_MS = 400L
        private const val TOGGLE_DEBOUNCE_MS = 500L
        private const val LONG_PRESS_MS = 500L
        private const val KILL_DELAY_MS = 3000L
        private const val BRIGHTNESS_STEP = 13
        private const val BRIGHTNESS_MIN = 4
    }
}
