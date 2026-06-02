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

    // Edit costs, lowest = most likely a real typo. Swapping two letters or hitting a physically
    // adjacent key are the common slips, so they win over arbitrary insert/delete/substitution.
    private const val COST_TRANSPOSE = 0
    private const val COST_ADJACENT = 1
    private const val COST_INDEL = 2     // insertion / deletion (a missed or doubled key)
    private const val COST_SUB = 3       // substitution of a non-adjacent key (least likely)

    /**
     * Keyboard-aware autocorrection: among the real words ([isKnown]) within a single edit of [word],
     * pick the most likely — preferring transpositions and adjacent-key slips (per [adjacency]), then
     * the most frequent ([freqOf]). Edit distance is capped at 1 to stay conservative (no surprise
     * replacements). Returns null if [word] is already a word or nothing confident is found.
     */
    fun bestCorrection(
        word: String,
        alphabet: String,
        adjacency: Map<Char, String>,
        isKnown: (String) -> Boolean,
        freqOf: (String) -> Long,
    ): String? {
        if (isKnown(word)) return null
        var best: String? = null
        var bestCost = Int.MAX_VALUE
        var bestFreq = -1L
        fun consider(cand: String, cost: Int) {
            if (cand == word || !isKnown(cand)) return
            val f = freqOf(cand)
            if (cost < bestCost || (cost == bestCost && f > bestFreq)) {
                bestCost = cost; bestFreq = f; best = cand
            }
        }
        for (i in 0..word.length) {
            val l = word.substring(0, i)
            val r = word.substring(i)
            if (r.isNotEmpty()) {
                consider(l + r.substring(1), COST_INDEL)                               // delete
                if (r.length > 1) consider(l + r[1] + r[0] + r.substring(2), COST_TRANSPOSE)
                val typed = r[0]
                val near = adjacency[typed].orEmpty()
                for (c in alphabet) {
                    if (c == typed) continue
                    consider(l + c + r.substring(1), if (c in near) COST_ADJACENT else COST_SUB)
                }
            }
            for (c in alphabet) consider(l + c + r, COST_INDEL)                         // insert
        }
        return best
    }

    /**
     * Build a key-adjacency map from keyboard [rows] (each a string of single-char keys). Two keys are
     * neighbours if they're in the same or an adjacent row within one column — a simple grid model
     * (ignoring stagger), which is plenty for typo correction.
     */
    fun adjacency(rows: List<String>): Map<Char, String> {
        val pos = HashMap<Char, Pair<Int, Int>>()
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> pos[ch] = r to c } }
        val out = HashMap<Char, String>()
        for ((ch, rc) in pos) {
            val (r, c) = rc
            val sb = StringBuilder()
            for ((other, orc) in pos) {
                if (other == ch) continue
                if (kotlin.math.abs(orc.first - r) <= 1 && kotlin.math.abs(orc.second - c) <= 1) sb.append(other)
            }
            out[ch] = sb.toString()
        }
        return out
    }
}
