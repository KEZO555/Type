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
 *   • **bundled** in the APK as an asset ([assetName]) — English only, always available; or
 *   • **downloaded** on demand into internal storage ([DictModel]) — every other language (Hebrew,
 *     Spanish, French, German, Italian, Portuguese, Arabic, Mandarin pinyin), fetched when enabled.
 *
 * Either way it's a `word<space>count` list, loaded into a frequency map and fed to the shared
 * keyboard-aware corrector ([WordPredict]): [correct] returns the most likely real word within one edit
 * of what was typed (preferring transpositions and adjacent-key slips), or — for longer words with no
 * single-edit fix — a conservative distance-2 fallback, or null.
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
    private val bigramFile = "${code}_bigrams.txt"
    private val adj = WordPredict.adjacency(adjacencyRows)
    private val letterSet = alphabet.toHashSet()

    private val main = Handler(Looper.getMainLooper())
    private val freq = HashMap<String, Long>(freqSizeHint)
    private val learned = HashMap<String, Long>()
    // Next-word model: prev word -> (next word -> times seen), learned from your own typing. Powers the
    // suggestion bar after a space, and biases completions of a partly-typed word toward what usually
    // follows the previous word.
    private val bigrams = HashMap<String, HashMap<String, Long>>()
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
                loadBigrams(app)
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
        learnedSorted = null   // rebuild the prefix index on first use after loading
    }

    private fun loadBigrams(context: Context) {
        val f = File(context.filesDir, bigramFile)
        if (!f.exists()) return
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val a = line.indexOf(' '); if (a <= 0) return@forEach
                val b = line.indexOf(' ', a + 1); if (b <= a + 1) return@forEach
                val c = line.substring(b + 1).toLongOrNull() ?: return@forEach
                bigrams.getOrPut(line.substring(0, a)) { HashMap() }[line.substring(a + 1, b)] = c
            }
        }
    }

    private val hebrew = code == "he"

    /** Is [w] a real word? Directly in the dictionary/learned, or — for Hebrew — a known stem with one or
     *  more glued-on proclitics (ו/ה/ש/ב/כ/ל/מ), e.g. "ושלום" = ו + שלום. */
    fun isWord(w: String): Boolean {
        if (freq.containsKey(w) || learned.containsKey(w)) return true
        if (hebrew) for ((_, stem) in TextOps.hebrewProcliticSplits(w)) {
            if (freq.containsKey(stem) || learned.containsKey(stem)) return true
        }
        return false
    }

    /** A *direct* dictionary/learned entry — the only thing we may autocorrect *to*. Unlike [isWord] this
     *  deliberately excludes Hebrew proclitic+stem reconstructions, so corrections never invent a glued
     *  surface form (e.g. ש/ו + a common stem) that isn't itself a real listed word. */
    private fun isDictWord(w: String): Boolean = freq.containsKey(w) || learned.containsKey(w)

    /** Raw count of a known word/stem (no proclitic handling). */
    private fun rawFreq(w: String): Long = freq[w] ?: learned[w]?.let { it * LEARN_WEIGHT } ?: 0L

    /** Ranking frequency: dictionary/learned count; for an unknown Hebrew surface form, its stem's count
     *  (discounted) so a proclitic+word reconstruction still ranks sensibly behind exact matches. */
    private fun effectiveFreq(w: String): Long {
        val direct = freq[w] ?: learned[w]?.let { it * LEARN_WEIGHT }
        if (direct != null) return direct
        if (hebrew) {
            var best = 0L
            for ((_, stem) in TextOps.hebrewProcliticSplits(w)) { val f = rawFreq(stem); if (f > best) best = f }
            if (best > 0) return best / 4
        }
        return 0L
    }

    /**
     * Best correction for [word], or null if it's already known / too short (< 3) / nothing confident.
     * Lowercased for lookup; the caller reapplies the original case. (Hebrew is caseless, so lowercasing
     * is a no-op there.)
     */
    fun correct(word: String, prevWord: String? = null): String? {
        if (!ready || word.length < 3) return null
        val w = word.lowercase()
        // The context-free result is memoized; a context-aware one depends on prevWord, so it's computed
        // fresh (still only when a word finishes / the bar updates, not in a tight loop).
        val ctx = prevWord?.lowercase()?.let { bigrams[it] }?.takeIf { it.isNotEmpty() }
        if (ctx == null && memo.containsKey(w)) return memo[w]
        val contextOf: (String) -> Long = if (ctx == null) NO_CONTEXT else { cand -> ctx[cand] ?: 0L }
        // sortedWords() enables the conservative distance-2 fallback (longer words only) at no per-key cost.
        // isWord stays permissive (so a correctly-typed prefixed word is left alone), but we only ever
        // correct *to* a real listed word via isDictWord — never to an invented proclitic+stem form.
        val fix = WordPredict.bestCorrection(
            w, alphabet, adj, ::isWord, { effectiveFreq(it) }, sortedWords(), contextOf, isTarget = ::isDictWord,
        )
        if (ctx == null) { if (memo.size > 4000) memo.clear(); memo[w] = fix }
        return fix
    }

    // Lexically-sorted view of the dictionary keys, built once on first use, for prefix completion: a
    // binary search to the prefix range then a short scan of just that range. freq is never mutated
    // after loading, so this stays valid; learned words (added later) are scanned separately below.
    @Volatile
    private var sorted: Array<String>? = null

    private fun sortedWords(): Array<String> =
        sorted ?: freq.keys.toTypedArray().also { it.sort(); sorted = it }

    // Sorted view of the learned-word keys, so prefix lookups in completions() are a binary search of the
    // matching run rather than a full scan of the (up to MAX_LEARNED) map on every keystroke. Rebuilt
    // lazily and invalidated (set to null) whenever the key set changes — a new word, forget, or clear.
    @Volatile
    private var learnedSorted: Array<String>? = null

    private fun learnedSortedArr(): Array<String> =
        learnedSorted ?: learned.keys.toTypedArray().also { it.sort(); learnedSorted = it }

    /**
     * Up to [limit] word completions of [prefix], most-frequent first (your learned words weighted in).
     * Empty until the dictionary is loaded, or for a prefix shorter than 2 (too many, unhelpful matches).
     * If [prevWord] is given, words that usually follow it (learned bigrams) are floated to the front.
     */
    fun completions(prefix: String, limit: Int = 3, prevWord: String? = null): List<String> {
        if (!ready) return emptyList()
        val p = prefix.lowercase()
        if (p.length < 2) return emptyList()
        // Learned words aren't in the sorted dictionary array, so pass the matching ones as extras
        // (weighted like effectiveFreq); the dictionary itself is ranked by raw frequency. Binary-search
        // the learned index to the prefix run instead of scanning the whole learned map each keystroke.
        var extra: HashMap<String, Long>? = null
        val ls = learnedSortedArr()
        var lo = 0; var hi = ls.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ls[mid] < p) lo = mid + 1 else hi = mid }
        var i = lo
        while (i < ls.size && ls[i].startsWith(p)) {
            val w = ls[i]; i++
            if (w !in freq) (extra ?: HashMap<String, Long>().also { extra = it })[w] = (learned[w] ?: 0L) * LEARN_WEIGHT
        }
        // Hebrew: also complete the stem under any glued proclitics — typing "ושל" suggests "ושלום" — by
        // completing the stem in the dictionary and re-attaching the prefix. Added as discounted extras so
        // they rank alongside the ordinary completions.
        if (hebrew) for ((prefix, stem) in TextOps.hebrewProcliticSplits(p)) {
            for (sc in WordPredict.completions(sortedWords(), stem, limit, { freq[it] ?: 0L })) {
                val cand = prefix + sc
                if (cand == p || freq.containsKey(cand)) continue
                val f = (freq[sc] ?: 0L) / 4
                val m = extra ?: HashMap<String, Long>().also { extra = it }
                if ((m[cand] ?: -1L) < f) m[cand] = f
            }
        }
        val base = WordPredict.completions(sortedWords(), p, limit, { freq[it] ?: 0L }, extra ?: emptyMap())
        val ctxMap = prevWord?.lowercase()?.let { bigrams[it] } ?: return base
        val ctx = WordPredict.topNext(ctxMap, limit, p).filter { it.length > p.length }
        if (ctx.isEmpty()) return base
        // Lead with the context predictions, then fill from the frequency-ranked completions.
        val out = LinkedHashSet<String>()
        for (w in ctx) { out.add(w); if (out.size >= limit) break }
        for (w in base) { if (out.size >= limit) break; out.add(w) }
        return out.toList()
    }

    /** Up to [limit] words that usually follow [prevWord] (learned next-word predictions), most-used first. */
    fun nextWords(prevWord: String, limit: Int = 3): List<String> {
        if (!ready) return emptyList()
        val m = bigrams[prevWord.lowercase()] ?: return emptyList()
        return WordPredict.topNext(m, limit)
    }

    /** Record that [next] was typed right after [prev] (a word pair), to grow the next-word model. */
    fun learnBigram(context: Context, prev: String, next: String) {
        if (!ready) return
        val p = prev.lowercase(); val n = next.lowercase()
        if (!validForLearn(p) || !validForLearn(n)) return
        appContext = context.applicationContext
        val m = bigrams[p]
        if (m == null) {
            if (bigrams.size >= MAX_BIGRAM_PREV) return   // keep the key set bounded; existing keys keep learning
            bigrams[p] = HashMap<String, Long>().apply { put(n, 1L) }
        } else {
            m[n] = (m[n] ?: 0L) + 1L
        }
        scheduleBigramSave()
    }

    private fun validForLearn(w: String): Boolean =
        w.isNotEmpty() && w.length <= maxLearnLen && w.all { it in letterSet }

    /** Remember a word the user typed (and kept). Becomes known and rankable; persisted (debounced).
     *  Only this language's own letters, length 2..[maxLearnLen]. */
    fun learn(context: Context, word: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.length > maxLearnLen || w.any { it !in letterSet }) return
        appContext = context.applicationContext
        val isNew = !isWord(w)
        val newKey = w !in learned                    // a brand-new learned key → the prefix index is stale
        learned[w] = (learned[w] ?: 0L) + 1L
        if (isNew) memo.clear() else memo.remove(w)   // a newly-known word changes corrections
        if (newKey) learnedSorted = null
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
            memo.clear(); learnedSorted = null; appContext = context.applicationContext; scheduleSave()
        }
    }

    /** Forget every learned word — and the next-word model, which is learned from the same typing. */
    fun clearLearned(context: Context) {
        learned.clear(); memo.clear(); bigrams.clear(); learnedSorted = null
        main.removeCallbacks(saveRunnable)
        main.removeCallbacks(bigramSaveRunnable)
        runCatching { File(context.applicationContext.filesDir, learnedFile).delete() }
        runCatching { File(context.applicationContext.filesDir, bigramFile).delete() }
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

    private val bigramSaveRunnable = Runnable { writeBigrams() }
    private fun scheduleBigramSave() {
        main.removeCallbacks(bigramSaveRunnable)
        main.postDelayed(bigramSaveRunnable, 5000)   // coalesce bursts of typing into one write
    }

    private fun writeBigrams() {
        val ctx = appContext ?: return
        val snapshot = ArrayList<Triple<String, String, Long>>()
        for ((p, m) in bigrams) for ((n, c) in m) snapshot.add(Triple(p, n, c))
        Thread {
            try {
                val top = snapshot.sortedByDescending { it.third }.take(MAX_BIGRAM_LINES)
                val sb = StringBuilder(top.size * 16)
                for (t in top) sb.append(t.first).append(' ').append(t.second).append(' ').append(t.third).append('\n')
                File(ctx.filesDir, bigramFile).writeText(sb.toString(), Charsets.UTF_8)
            } catch (e: Throwable) {
                Log.e(tag, "save bigrams failed", e)
            }
        }.start()
    }

    private companion object {
        const val LEARN_WEIGHT = 50_000L
        const val MAX_LEARNED = 2000
        const val MAX_BIGRAM_PREV = 4000     // cap distinct context words held in memory
        const val MAX_BIGRAM_LINES = 6000    // cap pairs persisted to disk (most-used kept)
        val NO_CONTEXT: (String) -> Long = { 0L }   // shared no-op so correct() allocates nothing
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
