package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

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
    // Key adjacency from the on-screen Hebrew layout, for keyboard-aware autocorrect.
    private val adj = WordPredict.adjacency(listOf("׳־קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"))

    // Learned words the user actually types. They become "known" (so they stop being autocorrected
    // away) and can be suggested. Each typed occurrence raises its count; the count maps to an
    // effective frequency via LEARN_WEIGHT so a word you use a lot competes with common dictionary
    // words. Persisted to internal storage.
    private const val LEARNED_FILE = "he_learned.txt"
    private const val LEARN_WEIGHT = 50_000L
    private const val MAX_LEARNED = 2000

    private val main = Handler(Looper.getMainLooper())
    private val freq = HashMap<String, Long>(48_000)
    private val learned = HashMap<String, Long>()
    private val memo = HashMap<String, String?>()   // word -> fix (null = checked, no correction)
    private var appContext: Context? = null

    @Volatile
    var ready = false
        private set
    private var loading = false

    /** Load the dictionary into memory (background). No-op once loaded / in flight. */
    fun prepare(context: Context) {
        if (ready || loading) return
        loading = true
        val app = context.applicationContext
        appContext = app
        Thread {
            try {
                load(app)
                loadLearned(app)
                main.post { ready = true; loading = false; Log.i(TAG, "loaded ${freq.size}+${learned.size} words") }
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

    private fun loadLearned(context: Context) {
        val f = File(context.filesDir, LEARNED_FILE)
        if (!f.exists()) return
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) return@forEach
                val w = line.substring(0, sp)
                val c = line.substring(sp + 1).toLongOrNull() ?: return@forEach
                learned[w] = c
            }
        }
    }

    fun isWord(w: String): Boolean = freq.containsKey(w) || learned.containsKey(w)

    /** Effective frequency for ranking: dictionary count, else a learned word's count × LEARN_WEIGHT. */
    private fun effectiveFreq(w: String): Long? =
        freq[w] ?: learned[w]?.let { it * LEARN_WEIGHT }

    /**
     * Remember a word the user typed (and kept). It becomes known and rankable. Hebrew letters only,
     * length 2..15. Persisted (debounced) to internal storage.
     */
    fun learn(context: Context, word: String) {
        if (word.length !in 2..15 || word.any { it < 'א' || it > 'ת' }) return
        appContext = context.applicationContext
        val isNew = !isWord(word)
        learned[word] = (learned[word] ?: 0L) + 1L
        if (isNew) memo.clear() else memo.remove(word)   // a newly-known word changes corrections
        scheduleSave()
    }

    private val saveRunnable = Runnable { writeLearned() }
    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 4000)   // coalesce bursts of typing into one write
    }

    private fun writeLearned() {
        val ctx = appContext ?: return
        val snapshot = ArrayList(learned.entries)
        Thread {
            try {
                val top = snapshot.sortedByDescending { it.value }.take(MAX_LEARNED)
                val sb = StringBuilder(top.size * 12)
                for (e in top) sb.append(e.key).append(' ').append(e.value).append('\n')
                File(ctx.filesDir, LEARNED_FILE).writeText(sb.toString(), Charsets.UTF_8)
            } catch (e: Throwable) {
                Log.e(TAG, "save learned failed", e)
            }
        }.start()
    }

    /**
     * Best correction for [word], or null if it's already a word / too short / nothing confident.
     * Only words of length ≥ 3 are corrected — two-letter Hebrew words (לא, את, …) are common and a
     * single edit reaches too many neighbours to correct safely.
     */
    fun correct(word: String): String? {
        if (!ready || word.length < 3) return null
        if (memo.containsKey(word)) return memo[word]
        val fix = WordPredict.bestCorrection(word, ALPHABET, adj, ::isWord) { effectiveFreq(it) ?: 0L }
        if (memo.size > 4000) memo.clear()   // bound the cache over a long session
        memo[word] = fix
        return fix
    }
}
