package app.lightphonekeyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Controls Android's colour-correction (daltonizer) filter through Settings.Secure — the mechanism
 * behind the Light Phone's always-on grayscale. Writing these keys needs WRITE_SECURE_SETTINGS,
 * grantable only over adb (once, survives reboots):
 *
 *   adb shell pm grant app.lightphonekeyboard android.permission.WRITE_SECURE_SETTINGS
 */
object ColorFilter {

    private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val DALTONIZER_MODE = "accessibility_display_daltonizer"

    /** Daltonizer mode 0 = simulate monochromacy, i.e. full grayscale. */
    private const val MODE_GRAYSCALE = 0

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isGrayscale(c: Context): Boolean =
        Settings.Secure.getInt(c.contentResolver, DALTONIZER_ENABLED, 0) == 1

    /** Enables or disables the grayscale filter. Returns true when the setting was actually written. */
    fun setGrayscale(c: Context, enabled: Boolean): Boolean {
        if (!hasPermission(c)) return false
        return try {
            if (enabled) Settings.Secure.putInt(c.contentResolver, DALTONIZER_MODE, MODE_GRAYSCALE)
            Settings.Secure.putInt(c.contentResolver, DALTONIZER_ENABLED, if (enabled) 1 else 0)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
