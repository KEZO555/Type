package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Bundled English word-frequency list (assets/en_words.txt, built by tools/gen_english.py) used for
 * word prediction — prefix completion. English autocorrect still goes through the device spell checker
 * (see [LightImeService]); this object only powers the suggestions strip. Like the Hebrew side it also
 * learns the words you type, persisted to internal storage, so your vocabulary surfaces over time.
 */
object EnglishWords {
    private const val TAG = "EnglishWords"
    private const val ASSET = "en_words.txt"
    private const val LEARNED_FILE = "en_learned.txt"
    private const val LEARN_WEIGHT = 50_000L
    private const val MAX_LEARNED = 2000
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
    // QWERTY key adjacency, for keyboard-aware autocorrect.
    private val adj = WordPredict.adjacency(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))

    private val main = Handler(Looper.getMainLooper())
    private val freq = HashMap<String, Long>(34_000)
    private val learned = HashMap<String, Long>()
    private val memo = HashMap<String, String?>()
    private var appContext: Context? = null
    private var sortedWords: Array<String> = emptyArray()
    private var sortedFreq: LongArray = LongArray(0)

    @Volatile
    var ready = false
        private set
    private var loading = false

    fun prepare(context: Context) {
        if (ready || loading) return
        loading = true
        val app = context.applicationContext
        appContext = app
        Thread {
            try {
                app.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { r ->
                    r.forEachLine { line ->
                        val sp = line.indexOf(' ')
                        if (sp <= 0) return@forEachLine
                        val c = line.substring(sp + 1).toLongOrNull() ?: return@forEachLine
                        freq[line.substring(0, sp)] = c
                    }
                }
                loadLearned(app)
                val keys = freq.keys.toTypedArray(); keys.sort()
                sortedWords = keys
                sortedFreq = LongArray(keys.size) { freq[keys[it]] ?: 0L }
                main.post { ready = true; loading = false; Log.i(TAG, "loaded ${freq.size}+${learned.size}") }
            } catch (e: Throwable) {
                main.post { loading = false }
                Log.e(TAG, "load failed", e)
            }
        }.start()
    }

    private fun loadLearned(context: Context) {
        val f = File(context.filesDir, LEARNED_FILE)
        if (!f.exists()) return
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) return@forEach
                val c = line.substring(sp + 1).toLongOrNull() ?: return@forEach
                learned[line.substring(0, sp)] = c
            }
        }
    }

    /** The best inline completion of [prefix] (lowercased), or null. */
    fun complete(prefix: String): String? {
        if (!ready) return null
        return WordPredict.bestCompletion(prefix.lowercase(), sortedWords, sortedFreq, learned, LEARN_WEIGHT)
    }

    fun isWord(w: String): Boolean = freq.containsKey(w) || learned.containsKey(w)

    private fun effectiveFreq(w: String): Long = freq[w] ?: learned[w]?.let { it * LEARN_WEIGHT } ?: 0L

    /** Autocorrection for [word] (length ≥ 3), lowercased; case is reapplied by the caller. */
    fun correct(word: String): String? {
        if (!ready || word.length < 3) return null
        val w = word.lowercase()
        if (memo.containsKey(w)) return memo[w]
        val fix = WordPredict.bestCorrection(w, ALPHABET, adj, ::isWord) { effectiveFreq(it) }
        memo[w] = fix
        return fix
    }

    /** Remember a word the user typed. ASCII letters only, length 2..20. */
    fun learn(context: Context, word: String) {
        val w = word.lowercase()
        if (w.length !in 2..20 || w.any { it < 'a' || it > 'z' }) return
        appContext = context.applicationContext
        val isNew = !isWord(w)
        learned[w] = (learned[w] ?: 0L) + 1L
        if (isNew) memo.clear() else memo.remove(w)
        scheduleSave()
    }

    private val saveRunnable = Runnable { writeLearned() }
    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 4000)
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
}
