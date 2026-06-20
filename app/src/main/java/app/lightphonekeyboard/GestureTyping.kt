package app.lightphonekeyboard

import kotlin.math.sqrt

/**
 * Decoder for gesture (swipe) typing: given the path a finger traced across the letter keys, find the
 * most likely word. Pure and layout-agnostic — it takes key centres and a word list, so it's unit-tested
 * on the host with no Android types.
 *
 * The same decoder is the "smart hybrid" for tap typists: a tap-typed word is just a path that visits one
 * point per key, so running [decode] over those tap points gives whole-word correction without swiping.
 *
 * Algorithm (a deliberately simple first cut, to be tuned on-device): a candidate word must begin at the
 * key nearest the path's start and end at the key nearest its end (a strong, cheap prune). Each surviving
 * word is scored by how closely its letters' key centres line up along the path — a greedy *monotonic*
 * alignment (each letter consumes path forward, never backward) — traded off against word frequency and
 * the previous-word context.
 */
object GestureTyping {

    class Key(val ch: Char, val x: Float, val y: Float)

    private const val ALIGN_WEIGHT = 6.0   // how much path mis-alignment outweighs frequency

    /**
     * Up to [limit] best words for the [xs]/[ys] path. [keys] are the letter key centres, [dict] the
     * lexically-sorted word list, [freqOf] its frequencies, [keyWidth] the key pitch (distances are
     * normalised by it), [contextOf] an optional previous-word→word bonus.
     */
    fun decode(
        xs: FloatArray,
        ys: FloatArray,
        keys: List<Key>,
        dict: Array<String>,
        freqOf: (String) -> Long,
        keyWidth: Float,
        contextOf: (String) -> Long = { 0L },
        limit: Int = 3,
    ): List<String> {
        val n = xs.size
        if (n < 2 || keys.isEmpty() || keyWidth <= 0f) return emptyList()
        val keyOf = HashMap<Char, Key>(keys.size)
        for (k in keys) if (k.ch !in keyOf) keyOf[k.ch] = k
        val startCh = nearest(keys, xs[0], ys[0]).ch
        val endCh = nearest(keys, xs[n - 1], ys[n - 1]).ch

        var bestW: String? = null
        var bestScore = -Double.MAX_VALUE
        val top = ArrayList<Pair<String, Double>>()
        for (w in dict) {
            if (w.length < 2 || w.first() != startCh || w.last() != endCh) continue
            val cost = alignmentCost(w, keyOf, xs, ys, keyWidth) ?: continue
            // lower alignment cost is better; frequency and context add log-scaled support
            val score = -cost * ALIGN_WEIGHT + ln1p(freqOf(w)) + 0.5 * ln1p(contextOf(w))
            insertTop(top, w, score, limit)
            if (score > bestScore) { bestScore = score; bestW = w }
        }
        if (bestW == null) return emptyList()
        return top.sortedByDescending { it.second }.map { it.first }
    }

    /** Normalised alignment cost of a specific [word] against the path — used by the tap-typing hybrid to
     *  compare a candidate against what was actually typed. Null if a letter isn't on the layout. */
    fun costOf(word: String, keys: List<Key>, xs: FloatArray, ys: FloatArray, keyWidth: Float): Double? {
        if (xs.isEmpty() || keyWidth <= 0f) return null
        val keyOf = HashMap<Char, Key>(keys.size)
        for (k in keys) if (k.ch !in keyOf) keyOf[k.ch] = k
        return alignmentCost(word, keyOf, xs, ys, keyWidth)
    }

    /** Greedy monotonic alignment: total normalised distance from each letter's key to the nearest path
     *  point at or after the previous letter's point. Null if a letter isn't on the layout. */
    private fun alignmentCost(w: String, keyOf: Map<Char, Key>, xs: FloatArray, ys: FloatArray, keyWidth: Float): Double? {
        val n = xs.size
        var p = 0
        var cost = 0.0
        for (i in w.indices) {
            val k = keyOf[w[i]] ?: return null
            var best = Float.MAX_VALUE
            var bestI = p
            var j = p
            while (j < n) {
                val dx = k.x - xs[j]; val dy = k.y - ys[j]
                val d = dx * dx + dy * dy
                if (d < best) { best = d; bestI = j }
                j++
            }
            cost += sqrt(best.toDouble()) / keyWidth
            p = bestI
        }
        return cost / w.length
    }

    private fun nearest(keys: List<Key>, x: Float, y: Float): Key {
        var best = keys[0]; var bd = Float.MAX_VALUE
        for (k in keys) { val dx = k.x - x; val dy = k.y - y; val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = k } }
        return best
    }

    private fun ln1p(v: Long): Double = if (v <= 0L) 0.0 else kotlin.math.ln(1.0 + v)

    private fun insertTop(top: ArrayList<Pair<String, Double>>, w: String, score: Double, limit: Int) {
        if (top.size < limit) { top.add(w to score); return }
        var minI = 0
        for (i in 1 until top.size) if (top[i].second < top[minI].second) minI = i
        if (score > top[minI].second) top[minI] = w to score
    }
}
