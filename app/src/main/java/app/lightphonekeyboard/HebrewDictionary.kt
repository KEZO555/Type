package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Bundled, fully-offline Hebrew dictionary + autocorrect. The device has no Hebrew spell checker on a
 * Light Phone, so — unlike the English path, which leans on the system [android.view.textservice.
 * SpellCheckerSession] — Hebrew ships its own word-frequency list (assets/he_words.txt, built by
 * tools/gen_hebrew.py) and a Norvig-style edit-distance corrector on top of it.
 *
 * Correction = pick the highest-frequency dictionary word within edit distance 1 of what was typed;
 * fall back to edit distance 2 only when nothing closer is known. A word already in the dictionary is
 * left untouched. Loaded once on a background thread; queries are synchronous and memoised, so the IME
 * can call [correct] inline when a word is finished.
 */
object HebrewDictionary {
    private const val TAG = "HebrewDictionary"
    private const val ASSET = "he_words.txt"
    /** The 27 Hebrew letter forms (alef..tav, finals included) used to generate edit candidates. */
    private const val ALPHABET = "אבגדהוזחטיךכלםמןנסעףפץצקרשת"

    private val main = Handler(Looper.getMainLooper())
    private val freq = HashMap<String, Long>(48_000)
    private val memo = HashMap<String, String?>()   // word -> fix (null = checked, no correction)

    @Volatile
    var ready = false
        private set
    private var loading = false

    /** Load the dictionary into memory (background). No-op once loaded / in flight. */
    fun prepare(context: Context) {
        if (ready || loading) return
        loading = true
        val app = context.applicationContext
        Thread {
            try {
                load(app)
                main.post { ready = true; loading = false; Log.i(TAG, "loaded ${freq.size} words") }
            } catch (e: Throwable) {
                main.post { loading = false }
                Log.e(TAG, "load failed", e)
            }
        }.start()
    }

    private fun load(context: Context) {
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) return@forEachLine
                val word = line.substring(0, sp)
                val count = line.substring(sp + 1).toLongOrNull() ?: return@forEachLine
                freq[word] = count
            }
        }
    }

    fun isWord(w: String): Boolean = freq.containsKey(w)

    /**
     * Best correction for [word], or null if it's already a word / too short / nothing confident.
     * Only words of length ≥ 3 are corrected — two-letter Hebrew words (לא, את, …) are common and a
     * single edit reaches too many neighbours to correct safely.
     */
    fun correct(word: String): String? {
        if (!ready || word.length < 3) return null
        memo[word]?.let { return it }
        if (memo.containsKey(word)) return null
        if (freq.containsKey(word)) { memo[word] = null; return null }

        val e1 = edits1(word)
        var best = bestKnown(e1)
        if (best == null) {
            // edit distance 2, bounded: only the known words reachable from e1's edits.
            val e2known = HashSet<String>()
            for (w in e1) for (c in edits1(w)) if (freq.containsKey(c)) e2known.add(c)
            best = bestKnown(e2known)
        }
        val fix = best?.takeIf { !it.equals(word, ignoreCase = false) }
        memo[word] = fix
        return fix
    }

    /** The most frequent dictionary word in [candidates], or null if none are known. */
    private fun bestKnown(candidates: Collection<String>): String? {
        var best: String? = null
        var bestFreq = -1L
        for (c in candidates) {
            val f = freq[c] ?: continue
            if (f > bestFreq) { bestFreq = f; best = c }
        }
        return best
    }

    /** All strings one edit (delete / transpose / replace / insert) away from [w]. */
    private fun edits1(w: String): Set<String> {
        val out = HashSet<String>()
        val n = w.length
        for (i in 0..n) {
            val l = w.substring(0, i)
            val r = w.substring(i)
            if (r.isNotEmpty()) {
                out.add(l + r.substring(1))                                   // delete
                if (r.length > 1) out.add(l + r[1] + r[0] + r.substring(2))   // transpose
                for (c in ALPHABET) out.add(l + c + r.substring(1))           // replace
            }
            for (c in ALPHABET) out.add(l + c + r)                            // insert
        }
        return out
    }
}
