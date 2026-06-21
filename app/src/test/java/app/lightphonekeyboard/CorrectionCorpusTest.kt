package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-trip regression test against the *real* shipped dictionaries: take the most common words, make
 * realistic typos (adjacent-key slips, transpositions, a dropped Hebrew matres ו/י), and check the
 * corrector recovers the original. Measures an aggregate recovery rate (printed) and fails if it drops
 * below a floor — so a change that breaks correction for common words is caught in CI, not by a user.
 *
 * It exercises the same pieces production uses: keyboard adjacency, Hebrew proclitic [isWord], correcting
 * only to a listed word ([isDictWord]), the matres cheap-indel, and the frequency-aware ranking — so the
 * two reported bugs (נרעה→נראה, לודה→תודה) and their whole classes stay fixed.
 */
class CorrectionCorpusTest {

    /** A language set up exactly as WordDictionary does, driving WordPredict.bestCorrection. */
    private class Lang(
        val freq: LinkedHashMap<String, Long>, val alphabet: String, rows: List<String>,
        val cheapIndel: Set<Char>, val hebrew: Boolean,
    ) {
        val adj = WordPredict.adjacency(rows)
        val sorted = freq.keys.toTypedArray().also { it.sort() }
        fun isDictWord(w: String) = freq.containsKey(w)
        fun isWord(w: String): Boolean {
            if (freq.containsKey(w)) return true
            if (hebrew) for ((_, stem) in TextOps.hebrewProcliticSplits(w)) if (freq.containsKey(stem)) return true
            return false
        }
        fun correct(word: String): String? {
            if (isWord(word) || word.length < 3) return null
            return WordPredict.bestCorrection(
                word, alphabet, adj, ::isWord, { freq[it] ?: 0L }, sorted,
                isTarget = ::isDictWord, cheapIndel = cheapIndel,
            )
        }

        var bigrams: Map<String, Map<String, Long>> = emptyMap()
        private fun pairCount(p: String, n: String) = bigrams[p]?.get(n) ?: 0L
        fun contextCorrect(word: String, prev: String): String? {
            if (pairCount(prev, word) > 0) return null
            val (cand, ctx) = WordPredict.bestContextNeighbor(word, adj, ::isDictWord) { pairCount(prev, it) } ?: return null
            return if (ctx >= 2L) cand else null
        }
    }

    private fun loadBigrams(vararg candidates: String): Map<String, Map<String, Long>> {
        val file = candidates.map { File(it) }.firstOrNull { it.exists() } ?: return emptyMap()
        val m = HashMap<String, HashMap<String, Long>>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val a = line.indexOf(' '); if (a <= 0) return@forEach
                val b = line.indexOf(' ', a + 1); if (b <= a + 1) return@forEach
                val c = line.substring(b + 1).trim().toLongOrNull() ?: return@forEach
                m.getOrPut(line.substring(0, a)) { HashMap() }[line.substring(a + 1, b)] = c
            }
        }
        return m
    }

    private fun loadFreq(vararg candidates: String): LinkedHashMap<String, Long> {
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("dictionary not found (cwd=${File(".").absolutePath}); tried: ${candidates.joinToString()}")
        val m = LinkedHashMap<String, Long>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val sp = line.indexOf(' '); if (sp <= 0) return@forEach
                val c = line.substring(sp + 1).trim().toLongOrNull() ?: return@forEach
                m[line.substring(0, sp)] = c
            }
        }
        return m
    }

    private val hebrew by lazy {
        Lang(
            loadFreq("../dicts/he.txt", "dicts/he.txt"),
            "אבגדהוזחטיךכלםמןנסעףפץצקרשת",
            listOf("׳־קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"),
            setOf('ו', 'י'), hebrew = true,
        ).also { it.bigrams = loadBigrams("../dicts/he_bigrams.txt", "dicts/he_bigrams.txt") }
    }
    private val english by lazy {
        Lang(
            loadFreq("src/main/assets/en_words.txt", "app/src/main/assets/en_words.txt"),
            "abcdefghijklmnopqrstuvwxyz",
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            emptySet(), hebrew = false,
        )
    }

    /** Every realistic single-slip typo of [w]: each adjacent-key substitution, each transposition, and
     *  (Hebrew) each dropped matres ו/י. */
    private fun typos(w: String, lang: Lang): Set<String> {
        val out = LinkedHashSet<String>()
        for (i in w.indices) for (n in lang.adj[w[i]].orEmpty()) out.add(w.substring(0, i) + n + w.substring(i + 1))
        for (i in 0 until w.length - 1) out.add(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        if (lang.hebrew) for (i in w.indices) if (w[i] == 'ו' || w[i] == 'י') out.add(w.substring(0, i) + w.substring(i + 1))
        return out
    }

    private fun recoveryRate(lang: Lang, topN: Int): Pair<Int, Int> {
        var total = 0; var ok = 0
        for ((w, _) in lang.freq.entries.take(topN)) {
            if (w.length < 4) continue                       // very short words are inherently ambiguous
            for (t in typos(w, lang)) {
                if (t == w || t.length < 3 || lang.isWord(t)) continue   // skip non-typos / typos that are real words
                total++
                if (lang.correct(t) == w) ok++
            }
        }
        return ok to total
    }

    @Test fun commonWordTyposRecover() {
        for ((name, lang) in listOf("Hebrew" to hebrew, "English" to english)) {
            val (ok, total) = recoveryRate(lang, topN = 800)
            val rate = ok.toDouble() / total
            println("[$name] common-word typo recovery: $ok/$total = ${"%.3f".format(rate)}")
            // Conservative floor for the first CI run (the printed rate above is used to tighten it next);
            // a healthy corrector is well above this, so it only trips on a gross regression.
            assertTrue("$name recovery $rate fell below floor — correction regressed", rate >= 0.45)
        }
    }

    @Test fun specificReportedCasesStayFixed() {
        // The exact cases reported, each a different root cause now guarded.
        assertEquals("נראה", hebrew.correct("נרעה"))   // frequency-aware: common substitution > rare transposition
        assertEquals("תודה", hebrew.correct("לודה"))   // proclitic: לו+דה is not a real word
        assertEquals("עכשיו", hebrew.correct("עכשיט"))  // plain adjacent-key fix
        assertEquals("the", english.correct("teh"))      // transposition
        assertEquals("receive", english.correct("recieve"))
    }

    @Test fun contextCorrectsAValidWordFromTheRealModel() {
        // כבה is a real word ("went out"), but after תודה the model strongly expects רבה ("thank you very
        // much"). Drives a valid → valid fix purely from the next-word model.
        assertEquals("רבה", hebrew.contextCorrect("כבה", "תודה"))
    }
}
