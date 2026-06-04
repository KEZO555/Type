package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure prediction logic shared by every language's dictionary: keyboard-aware
 * autocorrection and prefix completion. No Android dependencies, so these run on the host
 * (`./gradlew :app:testDebugUnitTest`) — the fast feedback loop the codebase otherwise lacks.
 */
class WordPredictTest {

    private val alphabet = "abcdefghijklmnopqrstuvwxyz"
    private val adj = WordPredict.adjacency(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))

    private val freqs = mapOf(
        "the" to 100L, "they" to 90L, "there" to 80L, "them" to 70L,
        "then" to 60L, "their" to 50L, "cat" to 10L, "car" to 8L,
    )
    private val known = freqs.keys
    private fun correct(w: String) =
        WordPredict.bestCorrection(w, alphabet, adj, { it in known }, { freqs[it] ?: 0L })

    // ---- autocorrect ----

    @Test fun leavesKnownWordsAlone() {
        assertNull(correct("the"))
        assertNull(correct("there"))
    }

    @Test fun fixesTransposition() {
        assertEquals("the", correct("teh"))   // swap of two adjacent letters is the cheapest edit
    }

    @Test fun prefersAdjacentKeyOverDistantSubstitution() {
        // "cay": y→t is an adjacent-key slip (→ "cat"); y→r is a distant substitution (→ "car").
        assertEquals("cat", correct("cay"))
    }

    @Test fun fixesSingleInsertion() {
        assertEquals("the", correct("the" + "e"))   // "thee" → drop a doubled key → "the"
    }

    @Test fun noConfidentFixReturnsNull() {
        assertNull(correct("xqz"))   // nothing within one edit of a known word
    }

    // ---- autocorrect: conservative distance-2 fallback (longer words) ----

    private val d2freqs = mapOf(
        "publicly" to 50L, "publication" to 5L, "because" to 100L,
        "receive" to 40L, "different" to 60L, "tomorrow" to 30L,
    )
    private val d2sorted = d2freqs.keys.toTypedArray().also { it.sort() }
    private fun correct2(w: String) =
        WordPredict.bestCorrection(w, alphabet, adj, { it in d2freqs }, { d2freqs[it] ?: 0L }, d2sorted)
    private fun correctNoDict(w: String) =
        WordPredict.bestCorrection(w, alphabet, adj, { it in d2freqs }, { d2freqs[it] ?: 0L })

    @Test fun fixesDistance2ForLongWords() {
        // "publically" → "publicly": two interior edits, same first (p) and last (y) letter.
        assertEquals("publicly", correct2("publically"))
    }

    @Test fun distance2NeedsTheDictionary() {
        // Without the sorted word list, the fallback can't run — stays at the conservative distance 1.
        assertNull(correctNoDict("publically"))
    }

    @Test fun distance2GatedByWordLength() {
        // "bcase" is two inserts from "because", but at 5 letters it's too short to fix confidently.
        assertNull(correct2("bcase"))
    }

    @Test fun distance2RequiresMatchingEnds() {
        // "publicar" is two edits from "publicly" but ends in a different letter, so it's left alone.
        assertNull(correct2("publicar"))
    }

    @Test fun distance1StillWinsWhenDictPresent() {
        // A plain transposition is a distance-1 fix and must still win even with the dict available.
        assertEquals("because", correct2("becuase"))
    }

    // ---- completions ----

    private val sorted = arrayOf("the", "they", "there", "them", "then", "their", "cat").also { it.sort() }
    private fun complete(prefix: String, limit: Int = 3, extra: Map<String, Long> = emptyMap()) =
        WordPredict.completions(sorted, prefix, limit, { freqs[it] ?: 0L }, extra)

    @Test fun completionsRankByFrequency() {
        // All five "the…" continuations; top three by frequency, excluding the bare prefix "the".
        assertEquals(listOf("they", "there", "them"), complete("the"))
    }

    @Test fun completionsExcludeTheExactPrefix() {
        assertTrue("the" !in complete("the", limit = 10))
    }

    @Test fun completionsHonourLimit() {
        assertEquals(listOf("they"), complete("the", limit = 1))
    }

    @Test fun shortPrefixYieldsNothing() {
        assertTrue(complete("t").isEmpty())
        assertTrue(complete("").isEmpty())
    }

    @Test fun unmatchedPrefixYieldsNothing() {
        assertTrue(complete("zz").isEmpty())
    }

    @Test fun learnedExtrasRankAlongsideDictionary() {
        // A heavily-weighted learned word ("thermos") outranks every dictionary completion.
        val out = complete("the", extra = mapOf("thermos" to 5_000_000L))
        assertEquals("thermos", out.first())
        assertEquals(3, out.size)
    }
}
