package app.lightphonekeyboard

import android.os.Bundle
import android.widget.TextView

/**
 * Key test screen: shows, live, every hardware key event the accessibility service can see — for
 * figuring out what a button (e.g. the LP3's side wheel) actually sends inside apps. If an input
 * produces no line here, it doesn't reach the service as a key event and can't be remapped by it.
 * LightOS template style (see [LightUi]).
 */
class KeyTestActivity : SettingsScreen() {

    private var log: TextView? = null
    private val lines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LightUi.screen(this, getString(R.string.setup_key_test)) { c ->
            LightUi.hint(c, getString(R.string.setup_key_test_sub))
            log = TextView(this).apply {
                textSize = 15f
                setTextColor(getColor(R.color.white))
                typeface = android.graphics.Typeface.MONOSPACE
                text = getString(R.string.key_test_waiting)
            }
            c.addView(log)
        })
    }

    override fun onResume() {
        super.onResume()
        KeyEventLog.listener = { line ->
            lines.addFirst(line)
            while (lines.size > MAX_LINES) lines.removeLast()
            log?.text = lines.joinToString("\n")
        }
    }

    override fun onPause() {
        KeyEventLog.listener = null
        super.onPause()
    }

    companion object {
        private const val MAX_LINES = 14
    }
}
