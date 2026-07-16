package app.lightphonekeyboard

/**
 * Tiny bridge between [ColorFilterService] and [KeyTestActivity]: while the test screen is open it
 * registers a listener and the service forwards every hardware key event it receives (single
 * process, both run on the main thread). No listener → zero overhead in the service.
 */
object KeyEventLog {
    @Volatile
    var listener: ((String) -> Unit)? = null

    val active: Boolean get() = listener != null

    fun push(line: String) {
        listener?.invoke(line)
    }
}
