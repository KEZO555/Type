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

    /**
     * Norvig-style autocorrection: the highest-frequency *known* word within edit distance 1 (then 2)
     * of [word], or null if [word] is already known or nothing confident is found. [isKnown] tells
     * whether a candidate is a real word (dictionary or learned); [freqOf] ranks the known candidates.
     * Used for both English and Hebrew so the two behave the same way.
     */
    fun bestCorrection(
        word: String,
        alphabet: String,
        isKnown: (String) -> Boolean,
        freqOf: (String) -> Long,
    ): String? {
        if (isKnown(word)) return null
        val e1 = edits1(word, alphabet)
        var best = pickBest(e1, isKnown, freqOf)
        if (best == null) {
            val e2 = HashSet<String>()
            for (w in e1) for (c in edits1(w, alphabet)) if (isKnown(c)) e2.add(c)
            best = pickBest(e2, isKnown, freqOf)
        }
        return best?.takeIf { it != word }
    }

    private fun pickBest(cands: Collection<String>, isKnown: (String) -> Boolean, freqOf: (String) -> Long): String? {
        var best: String? = null
        var bestFreq = -1L
        for (c in cands) {
            if (!isKnown(c)) continue
            val f = freqOf(c)
            if (f > bestFreq) { bestFreq = f; best = c }
        }
        return best
    }

    /** All strings one edit (delete / transpose / replace / insert over [alphabet]) away from [w]. */
    private fun edits1(w: String, alphabet: String): Set<String> {
        val out = HashSet<String>()
        for (i in 0..w.length) {
            val l = w.substring(0, i)
            val r = w.substring(i)
            if (r.isNotEmpty()) {
                out.add(l + r.substring(1))                                   // delete
                if (r.length > 1) out.add(l + r[1] + r[0] + r.substring(2))   // transpose
                for (c in alphabet) out.add(l + c + r.substring(1))           // replace
            }
            for (c in alphabet) out.add(l + c + r)                            // insert
        }
        return out
    }
}
