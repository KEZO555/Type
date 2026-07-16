package app.lightphonekeyboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Key shortcuts & more: the Light Phone utilities — pausing the always-on grayscale per app,
 * hardware keymaps, wheel brightness and close-on-lock (see [ColorFilterService]). LightOS
 * template style (see [LightUi]).
 */
class ColorSettingsActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LightUi.screen(this, getString(R.string.section_color)) { c ->
            LightUi.hint(c, getString(R.string.setup_color_blurb))
            if (!ColorFilter.hasPermission(this)) {
                LightUi.hint(c, getString(R.string.setup_color_permission))
            }
            LightUi.navItem(c, getString(R.string.setup_color_service), getString(R.string.setup_color_service_sub)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            LightUi.navItem(c, getString(R.string.setup_color_apps), getString(R.string.setup_color_apps_sub)) {
                startActivity(Intent(this, ColorAppsActivity::class.java))
            }
            cycleItem(c, "Colour keymap", R.string.setup_color_keymap_sub,
                listOf("Off", "Camera long-press", "Volume up + down", "Double volume up", "Double volume down"),
                { Prefs.colorKeymap(this) }, { Prefs.setColorKeymap(this, it) })
            cycleItem(c, "Recents keymap", R.string.setup_recents_keymap_sub,
                listOf("Off", "Camera long-press", "Volume up + down", "Double volume up", "Double volume down"),
                { Prefs.recentsKeymap(this) }, { Prefs.setRecentsKeymap(this, it) })
            toggleItem(c, R.string.setup_wheel_brightness, R.string.setup_wheel_brightness_sub,
                { Prefs.wheelBrightness(this) },
                {
                    Prefs.setWheelBrightness(this, it)
                    // Brightness writes need the user-grantable "Modify system settings" appop.
                    if (it && !Settings.System.canWrite(this)) {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                        )
                    }
                })
            toggleItem(c, R.string.setup_wheel_back, R.string.setup_wheel_back_sub,
                { Prefs.wheelPressBack(this) }, { Prefs.setWheelPressBack(this, it) })
            toggleItem(c, R.string.setup_close_on_lock, R.string.setup_close_on_lock_sub,
                { Prefs.closeAppsOnLock(this) }, { Prefs.setCloseAppsOnLock(this, it) })
            LightUi.navItem(c, getString(R.string.setup_key_test), getString(R.string.setup_key_test_menu_sub)) {
                startActivity(Intent(this, KeyTestActivity::class.java))
            }
        })
    }
}
