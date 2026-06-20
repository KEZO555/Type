package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-side tests for the pure swipe decoder. A simple one-row layout keeps the geometry obvious. */
class GestureTypingTest {

    // c a t o  along a row, with 'o' lifted off the row so a path that stays on the row avoids it.
    private val keys = listOf(
        GestureTyping.Key('c', 0f, 0f),
        GestureTyping.Key('a', 10f, 0f),
        GestureTyping.Key('t', 20f, 0f),
        GestureTyping.Key('o', 10f, 20f),
    )
    private val keyWidth = 10f
    private val dict = arrayOf("cat", "cot", "ct").also { it.sort() }
    private val freq = mapOf("cat" to 100L, "cot" to 80L, "ct" to 1L)

    private fun decode(xs: FloatArray, ys: FloatArray, ctx: (String) -> Long = { 0L }) =
        GestureTyping.decode(xs, ys, keys, dict, { freq[it] ?: 0L }, keyWidth, ctx)

    @Test fun decodesAStraightSwipeAcrossTheRow() {
        // c → a → t along y=0: passes through 'a', not the lifted 'o', so "cat" beats "cot"; beats "ct" on freq.
        val xs = floatArrayOf(0f, 5f, 10f, 15f, 20f)
        val ys = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        assertEquals("cat", decode(xs, ys).first())
    }

    @Test fun aDipTowardOFavoursCot() {
        // Same start/end, but the path dips down toward 'o' (y up to 20) → "cot" aligns better than "cat".
        val xs = floatArrayOf(0f, 5f, 10f, 15f, 20f)
        val ys = floatArrayOf(0f, 10f, 20f, 10f, 0f)
        assertEquals("cot", decode(xs, ys).first())
    }

    @Test fun requiresMatchingStartAndEndKeys() {
        // A path that starts at 'a' (x=10) can't produce any of these words (all start with c) → empty.
        val xs = floatArrayOf(10f, 15f, 20f)
        val ys = floatArrayOf(0f, 0f, 0f)
        assertTrue(decode(xs, ys).isEmpty())
    }
}
