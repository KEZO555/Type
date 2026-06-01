package app.lightphonekeyboard

/**
 * Shared word-prediction helper: top-[n] completions of a prefix, drawn from a bundled frequency map
 * plus a learned-words map (learned counts are scaled by [learnWeight] so frequently-used personal
 * words compete with common dictionary words). Only words *longer* than the prefix are returned — the
 * user has already typed the prefix itself.
 *
 * A linear scan with a tiny fixed-size top-list; fast enough to run on every keystroke for the ~30–40k
 * word lists we ship.
 */
fun topCompletions(
    prefix: String,
    n: Int,
    freq: Map<String, Long>,
    learned: Map<String, Long>,
    learnWeight: Long,
): List<String> {
    if (prefix.isEmpty() || n <= 0) return emptyList()
    val words = ArrayList<String>(n)
    val scores = ArrayList<Long>(n)

    fun offer(w: String, score: Long) {
        if (w.length <= prefix.length || !w.startsWith(prefix)) return
        // Insert into the descending-by-score top list (size ≤ n).
        var i = 0
        while (i < words.size && scores[i] >= score) i++
        if (i >= n) return
        words.add(i, w); scores.add(i, score)
        if (words.size > n) { words.removeAt(n); scores.removeAt(n) }
    }

    for ((w, f) in freq) offer(w, f)
    for ((w, c) in learned) if (!freq.containsKey(w)) offer(w, c * learnWeight)
    return words
}
