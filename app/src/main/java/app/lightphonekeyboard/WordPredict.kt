package app.lightphonekeyboard

/**
 * Shared word-prediction helper. The bundled word list is held as a string array sorted lexically with
 * a parallel frequency array, so a completion lookup is a binary search to the prefix range plus a
 * short linear scan of just that range — fast enough to run on every keystroke. Learned words (a small
 * map) are scanned too, their counts scaled by [learnWeight] so personal words can win.
 */
object WordPredict {

    /** First index in [sorted] whose entry is ≥ [key] (standard lower-bound binary search). */
    private fun lowerBound(sorted: Array<String>, key: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * The single best completion of [prefix] — a longer word that starts with it, highest frequency
     * first — or null if there's none.
     */
    fun bestCompletion(
        prefix: String,
        sorted: Array<String>,
        freqs: LongArray,
        learned: Map<String, Long>,
        learnWeight: Long,
    ): String? {
        if (prefix.isEmpty()) return null
        var best: String? = null
        var bestScore = -1L
        var i = lowerBound(sorted, prefix)
        while (i < sorted.size && sorted[i].startsWith(prefix)) {
            if (sorted[i].length > prefix.length && freqs[i] > bestScore) {
                bestScore = freqs[i]; best = sorted[i]
            }
            i++
        }
        for ((w, c) in learned) {
            if (w.length > prefix.length && w.startsWith(prefix)) {
                val f = c * learnWeight
                if (f > bestScore) { bestScore = f; best = w }
            }
        }
        return best
    }
}
