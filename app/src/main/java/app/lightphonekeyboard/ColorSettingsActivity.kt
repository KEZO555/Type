package app.lightphonekeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Colour filter screen: the Light Phone utilities that pause the phone's always-on grayscale per
 * app (see [ColorFilterService]). LightOS template style (see [LightUi]).
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
            toggleItem(c, R.string.setup_close_on_lock, R.string.setup_close_on_lock_sub,
                { Prefs.closeAppsOnLock(this) }, { Prefs.setCloseAppsOnLock(this, it) })
        })
    }
}
