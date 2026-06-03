package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Offline autocorrect dictionary for a language whose word list is downloaded on demand (Spanish,
 * French, German, Italian, Portuguese). English and Hebrew are bundled in the APK and keep their own
 * objects ([EnglishWords] / [HebrewDictionary]); this is the same engine for the rest.
 *
 * The downloaded "word<space>count" file ([DictModel]) is loaded into a frequency map and fed to the
 * shared keyboard-aware, edit-distance-1 corrector ([WordPredict]). Like the bundled languages it also
 * learns the words you type — kept in a per-language file and weighted so your own vocabulary wins.
 *
 * One instance per language code, created lazily by [Dictionaries].
 */
class WordDictionary(
    private val code: String,
    private val alphabet: String,           // letters used to generate edit candidates
    adjacencyRows: List<String>,            // the layout's letter rows, for keyboard-aware costs
) {
    private val tag = "Dict-$code"
    private val learnedFile = "${code}_learned.txt"
    private val adj = WordPredict.adjacency(adjacencyRows)
    private val letterSet = alphabet.toHashSet()

    private val main = Handler(Looper.getMainLooper())
    private val freq = HashMap<String, Long>(20_000)
    private val learned = HashMap<String, Long>()
    private val memo = HashMap<String, String?>()
    private var appContext: Context? = null

    @Volatile
    var ready = false
        private set
    private var loading = false

    fun isInstalled(context: Context): Boolean = DictModel.isInstalled(context, code)

    /** Load the downloaded dictionary into memory (background). No-op if loaded, in flight, or absent. */
    fun prepare(context: Context) {
        if (ready || loading) return
        val app = context.applicationContext
        val file = DictModel.dictFile(app, code)
        if (!file.exists()) return          // not downloaded yet — nothing to load
        loading = true
        appContext = app
        Thread {
            try {
                file.bufferedReader(Charsets.UTF_8).use { r ->
                    r.forEachLine { line ->
                        val sp = line.indexOf(' ')
                        if (sp <= 0) return@forEachLine
                        val c = line.substring(sp + 1).toLongOrNull() ?: return@forEachLine
                        freq[line.substring(0, sp)] = c
                    }
                }
                loadLearned(app)
                main.post { ready = true; loading = false; Log.i(tag, "loaded ${freq.size}+${learned.size}") }
            } catch (e: Throwable) {
                main.post { loading = false }
                Log.e(tag, "load failed", e)
            }
        }.start()
    }

    private fun loadLearned(context: Context) {
        val f = File(context.filesDir, learnedFile)
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

    fun isWord(w: String): Boolean = freq.containsKey(w) || learned.containsKey(w)

    private fun effectiveFreq(w: String): Long = freq[w] ?: learned[w]?.let { it * LEARN_WEIGHT } ?: 0L

    /** Autocorrection for [word] (length ≥ 3), lowercased; case is reapplied by the caller. */
    fun correct(word: String): String? {
        if (!ready || word.length < 3) return null
        val w = word.lowercase()
        if (memo.containsKey(w)) return memo[w]
        val fix = WordPredict.bestCorrection(w, alphabet, adj, ::isWord) { effectiveFreq(it) }
        if (memo.size > 4000) memo.clear()
        memo[w] = fix
        return fix
    }

    /** Remember a word the user typed (and kept). Becomes known and rankable; persisted (debounced). */
    fun learn(context: Context, word: String) {
        val w = word.lowercase()
        if (w.length !in 2..20 || w.any { it !in letterSet }) return
        appContext = context.applicationContext
        val isNew = !isWord(w)
        learned[w] = (learned[w] ?: 0L) + 1L
        if (isNew) memo.clear() else memo.remove(w)
        scheduleSave()
    }

    /** Words you've taught the keyboard, most-used first (loaded from disk if not already in memory). */
    fun learnedWords(context: Context): List<String> {
        if (learned.isEmpty()) { appContext = context.applicationContext; loadLearned(context.applicationContext) }
        return learned.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Forget one learned word. */
    fun forget(context: Context, word: String) {
        if (learned.remove(word.lowercase()) != null) {
            memo.clear(); appContext = context.applicationContext; scheduleSave()
        }
    }

    /** Forget every learned word. */
    fun clearLearned(context: Context) {
        learned.clear(); memo.clear()
        main.removeCallbacks(saveRunnable)
        runCatching { File(context.applicationContext.filesDir, learnedFile).delete() }
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
                File(ctx.filesDir, learnedFile).writeText(sb.toString(), Charsets.UTF_8)
            } catch (e: Throwable) {
                Log.e(tag, "save learned failed", e)
            }
        }.start()
    }

    private companion object {
        const val LEARN_WEIGHT = 50_000L
        const val MAX_LEARNED = 2000
    }
}

/**
 * Lazily-built registry of the downloadable-dictionary languages, keyed by code. Returns null for
 * English/Hebrew (which have their own bundled objects) and for any language without a [LangDef.dictUrl].
 */
object Dictionaries {
    private val instances = HashMap<String, WordDictionary>()

    fun get(code: String): WordDictionary? {
        val def = Languages.byCode(code)
        if (def.code != code || def.dictUrl == null) return null
        return instances.getOrPut(code) {
            WordDictionary(code, def.autocorrectAlphabet, def.letterRows)
        }
    }
}
