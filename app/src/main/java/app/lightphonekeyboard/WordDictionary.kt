package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File

/**
 * The keyboard's offline autocorrect dictionary — one instance per language, configured by
 * [Dictionaries]. The word list comes from one of two places:
 *
 *   • **bundled** in the APK as an asset ([assetName]) — English and Hebrew, always available; or
 *   • **downloaded** on demand into internal storage ([DictModel]) — Spanish, French, German,
 *     Italian, Portuguese.
 *
 * Either way it's a `word<space>count` list, loaded into a frequency map and fed to the shared
 * keyboard-aware, edit-distance-1 corrector ([WordPredict]): [correct] returns the most likely real
 * word within one edit of what was typed (preferring transpositions and adjacent-key slips), or null.
 * The keyboard also [learn]s the words you type — weighted so your own vocabulary competes with common
 * words and persisted per language — so they stop being "corrected" and can win as suggestions.
 */
class WordDictionary(
    private val code: String,
    private val alphabet: String,            // letters used to generate edit candidates
    adjacencyRows: List<String>,             // layout letter rows, for keyboard-aware edit costs
    private val assetName: String? = null,   // bundled source; null → downloaded file in filesDir
    private val maxLearnLen: Int = 20,       // longest word we'll learn (Hebrew caps lower)
    freqSizeHint: Int = 32_000,
) {
    private val tag = "Dict-$code"
    private val learnedFile = "${code}_learned.txt"
    private val adj = WordPredict.adjacency(adjacencyRows)
    private val letterSet = alphabet.toHashSet()

    private val main = Handler(Looper.getMainLooper())
    private val freq = HashMap<String, Long>(freqSizeHint)
    private val learned = HashMap<String, Long>()
    private val memo = HashMap<String, String?>()   // word -> fix (null = checked, no correction)
    private var appContext: Context? = null

    @Volatile
    var ready = false
        private set
    private var loading = false

    /** Whether the word list is present: always for a bundled language, file-dependent for a download. */
    fun isInstalled(context: Context): Boolean =
        assetName != null || DictModel.isInstalled(context, code)

    /** Load the word list into memory (background). No-op once loaded / in flight, or — for a
     *  downloadable language — if it hasn't been downloaded yet. */
    fun prepare(context: Context) {
        if (ready || loading) return
        val app = context.applicationContext
        if (assetName == null && !DictModel.dictFile(app, code).exists()) return
        loading = true
        appContext = app
        Thread {
            try {
                openReader(app).use { r ->
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

    private fun openReader(context: Context): BufferedReader =
        if (assetName != null) context.assets.open(assetName).bufferedReader(Charsets.UTF_8)
        else DictModel.dictFile(context, code).bufferedReader(Charsets.UTF_8)

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

    /** Ranking frequency: dictionary count, else a learned word's count × LEARN_WEIGHT, else 0. */
    private fun effectiveFreq(w: String): Long = freq[w] ?: learned[w]?.let { it * LEARN_WEIGHT } ?: 0L

    /**
     * Best correction for [word], or null if it's already known / too short (< 3) / nothing confident.
     * Lowercased for lookup; the caller reapplies the original case. (Hebrew is caseless, so lowercasing
     * is a no-op there.)
     */
    fun correct(word: String): String? {
        if (!ready || word.length < 3) return null
        val w = word.lowercase()
        if (memo.containsKey(w)) return memo[w]
        val fix = WordPredict.bestCorrection(w, alphabet, adj, ::isWord) { effectiveFreq(it) }
        if (memo.size > 4000) memo.clear()   // bound the cache over a long session
        memo[w] = fix
        return fix
    }

    // Lexically-sorted view of the dictionary keys, built once on first use, for prefix completion: a
    // binary search to the prefix range then a short scan of just that range. freq is never mutated
    // after loading, so this stays valid; learned words (added later) are scanned separately below.
    @Volatile
    private var sorted: Array<String>? = null

    private fun sortedWords(): Array<String> =
        sorted ?: freq.keys.toTypedArray().also { it.sort(); sorted = it }

    /**
     * Up to [limit] word completions of [prefix], most-frequent first (your learned words weighted in).
     * Empty until the dictionary is loaded, or for a prefix shorter than 2 (too many, unhelpful matches).
     */
    fun completions(prefix: String, limit: Int = 3): List<String> {
        if (!ready) return emptyList()
        val p = prefix.lowercase()
        if (p.length < 2) return emptyList()
        // Learned words aren't in the sorted dictionary array, so pass the matching ones as extras
        // (weighted like effectiveFreq); the dictionary itself is ranked by raw frequency.
        var extra: HashMap<String, Long>? = null
        for ((w, c) in learned) {
            if (w !in freq && w.startsWith(p)) (extra ?: HashMap<String, Long>().also { extra = it })[w] = c * LEARN_WEIGHT
        }
        return WordPredict.completions(sortedWords(), p, limit, { freq[it] ?: 0L }, extra ?: emptyMap())
    }

    /** Remember a word the user typed (and kept). Becomes known and rankable; persisted (debounced).
     *  Only this language's own letters, length 2..[maxLearnLen]. */
    fun learn(context: Context, word: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.length > maxLearnLen || w.any { it !in letterSet }) return
        appContext = context.applicationContext
        val isNew = !isWord(w)
        learned[w] = (learned[w] ?: 0L) + 1L
        if (isNew) memo.clear() else memo.remove(w)   // a newly-known word changes corrections
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
 * The dictionary for each language, built lazily and cached. Every supported language has one (English
 * and Hebrew bundled in the APK; the rest downloaded on demand), so callers don't special-case codes.
 * Returns null only for an unknown code or a language with no dictionary configured.
 */
object Dictionaries {
    private val instances = HashMap<String, WordDictionary>()

    fun get(code: String): WordDictionary? {
        val def = Languages.byCode(code)
        if (def.code != code) return null                       // byCode falls back to EN for unknowns
        if (def.dictAsset == null && def.dictUrl == null) return null
        return instances.getOrPut(code) { build(def) }
    }

    private fun build(def: LangDef): WordDictionary = when (def.code) {
        // Hebrew is caseless, and its layout has two symbol keys (geresh, maqaf) before the letters,
        // which shift the column alignment the key-adjacency model depends on — so it keeps the exact
        // alphabet and rows the keyboard-aware corrector was tuned against, plus a shorter learn cap.
        "he" -> WordDictionary(
            code = "he",
            alphabet = "אבגדהוזחטיךכלםמןנסעףפץצקרשת",
            adjacencyRows = listOf("׳־קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"),
            assetName = def.dictAsset,
            maxLearnLen = 15,
            freqSizeHint = 48_000,
        )
        // English corrects over plain a–z — its bundled dictionary has no accents, unlike the other
        // Latin languages whose alphabets include their accented letters (derived from the layout).
        "en" -> WordDictionary(
            code = "en",
            alphabet = "abcdefghijklmnopqrstuvwxyz",
            adjacencyRows = def.letterRows,
            assetName = def.dictAsset,
            freqSizeHint = 34_000,
        )
        else -> WordDictionary(
            code = def.code,
            alphabet = def.autocorrectAlphabet,
            adjacencyRows = def.letterRows,
        )
    }
}
