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

    // ---- autocorrect: context (previous-word) tie-break ----

    // "ct" is one insertion from both "cat" and "cot" — equally likely edits.
    private val ctxFreqs = mapOf("cat" to 100L, "cot" to 50L)
    private fun correctCtx(w: String, context: (String) -> Long) =
        WordPredict.bestCorrection(w, alphabet, adj, { it in ctxFreqs }, { ctxFreqs[it] ?: 0L }, contextOf = context)

    @Test fun withoutContextMostFrequentTieWins() {
        assertEquals("cat", correctCtx("ct") { 0L })
    }

    @Test fun contextFloatsTheExpectedNextWordAboveAMoreFrequentOne() {
        // The previous word is usually followed by "cot", so it wins despite "cat" being more frequent.
        assertEquals("cot", correctCtx("ct") { if (it == "cot") 1L else 0L })
    }

    // ---- autocorrect: context promotes across an edit-cost step (not just same-cost ties) ----

    @Test fun contextPromotesACostlierButExpectedWord() {
        // "ot": the cheapest fix is the transposition → "to" (cost 0, no context). "it" is one step
        // costlier (o→i is an adjacent-key slip, cost 1) but is what the previous word expects, so it wins.
        val known = setOf("to", "it")
        val freq = mapOf("to" to 100L, "it" to 50L)
        fun corr(ctx: (String) -> Long) =
            WordPredict.bestCorrection("ot", alphabet, adj, { it in known }, { freq[it] ?: 0L }, contextOf = ctx)
        assertEquals("to", corr { 0L })                                 // no context → cheapest edit wins
        assertEquals("it", corr { if (it == "it") 3L else 0L })         // context floats the costlier fit
    }

    @Test fun contextPromotionStaysWithinOneEditStep() {
        // A context-supported word two edit-steps costlier is too far — the cheap, context-free fix holds.
        // "ot": "to" is the cost-0 transposition; "at" needs the far substitution o→a (cost 3 > 0 + 1),
        // so context can't pull it across that gap.
        val known = setOf("to", "at")
        val freq = mapOf("to" to 100L, "at" to 50L)
        assertEquals("to", WordPredict.bestCorrection(
            "ot", alphabet, adj, { it in known }, { freq[it] ?: 0L }, contextOf = { if (it == "at") 9L else 0L }))
    }

    // ---- autocorrect: never invent a non-dictionary target (the Hebrew proclitic bug) ----

    @Test fun doesNotCorrectToAKnownButNonDictionaryForm() {
        // Mirrors Hebrew: "scat" is accepted as a word only because it parses as proclitic "s" + stem
        // "cat" — it isn't a real listed entry. A typo must not be "corrected" into that invented form.
        val isKnown = { w: String -> w == "cat" || w == "scat" }
        val isTarget = { w: String -> w == "cat" }
        val freq = { w: String -> if (w == "scat") 1000L else 10L }
        // With the permissive predicate as the target, the corrector invents "scat":
        assertEquals("scat", WordPredict.bestCorrection("sct", alphabet, adj, isKnown, freq))
        // With a strict target it refuses — there is no real word one edit from "sct":
        assertNull(WordPredict.bestCorrection("sct", alphabet, adj, isKnown, freq, isTarget = isTarget))
    }

    // ---- autocorrect: spatial (touch) cost steers the substitution ----

    @Test fun spatialCostSteersAmbiguousSubstitutionAgainstTheKeyGrid() {
        // "cay": by the key grid, y→t is adjacent (cheap) and y→r is a far substitution (costly), so the
        // grid alone fixes it to "cat". A spatial model that saw the finger land nearest 'r' should win.
        val known = setOf("cat", "car")
        val subCost = { pos: Int, from: Char, to: Char ->
            if (pos == 2 && from == 'y') (if (to == 'r') 0 else if (to == 't') 1 else null) else null
        }
        fun corr(sc: (Int, Char, Char) -> Int?) =
            WordPredict.bestCorrection("cay", alphabet, adj, { it in known }, { 0L }, subCost = sc)
        assertEquals("cat", corr { _, _, _ -> null })   // grid only: adjacent 't' beats far 'r'
        assertEquals("car", corr(subCost))              // touch said 'r' → overrides the grid
    }

    // ---- confidence: report the winning edit cost (gates auto-apply) ----

    @Test fun reportsWinningEditCostForConfidenceGating() {
        val known = setOf("the", "cat")
        val c0 = IntArray(1)
        assertEquals("the", WordPredict.bestCorrection("teh", alphabet, adj, { it in known }, { 0L }, costOut = c0))
        assertTrue(c0[0] <= WordPredict.CONFIDENT_MAX_COST)   // transposition → confident, auto-applies
        val c1 = IntArray(1)
        assertEquals("cat", WordPredict.bestCorrection("cpt", alphabet, adj, { it in known }, { 0L }, costOut = c1))
        assertTrue(c1[0] > WordPredict.CONFIDENT_MAX_COST)    // far substitution → offered, not auto-applied
    }

    // ---- noisy-channel: a much more frequent word beats a cheaper rare edit ----

    @Test fun aMuchMoreFrequentWordBeatsACheaperRareEdit() {
        // "no": the cheapest edit is the transposition → "on" (rare), but "go" (one substitution, far
        // commoner) is the likelier intent. Mirrors נרעה → נראה over the rarer נערה.
        val known = setOf("on", "go")
        val freq = mapOf("on" to 5L, "go" to 1000L)
        assertEquals("go", WordPredict.bestCorrection("no", alphabet, adj, { it in known }, { freq[it] ?: 0L }))
    }

    @Test fun aSmallFrequencyEdgeDoesNotOverrideACheaperEdit() {
        // Only ~2× more frequent → the cheaper transposition "on" still wins (promotion stays conservative).
        val known = setOf("on", "go")
        val freq = mapOf("on" to 50L, "go" to 100L)
        assertEquals("on", WordPredict.bestCorrection("no", alphabet, adj, { it in known }, { freq[it] ?: 0L }))
    }

    // ---- cheap matres-lectionis indel (Hebrew ktiv male/haser) ----

    @Test fun cheapMatresIndelBeatsAMoreFrequentPlainInsertion() {
        // Stand-in for Hebrew ו/י: inserting the "optional" letter is a cheap edit, so it wins over a more
        // frequent but ordinary insertion. (In Hebrew: היתה → הייתה rather than some commoner near-word.)
        val known = setOf("cat", "cit")
        val freq = mapOf("cat" to 100L, "cit" to 60L)   // comparable freqs (not a lopsided gap)
        fun corr(cheap: Set<Char>) =
            WordPredict.bestCorrection("ct", alphabet, adj, { it in known }, { freq[it] ?: 0L }, cheapIndel = cheap)
        assertEquals("cat", corr(emptySet()))     // plain: both cost the same, most frequent wins
        assertEquals("cit", corr(setOf('i')))      // 'i' is "optional" → its insertion is cheaper, so it wins
    }

    // ---- run-on split correction ----

    @Test fun splitsACleanRunOnIntoTwoWords() {
        val known = setOf("this", "is", "in", "fact")
        val out = WordPredict.splitCorrection("thisis", { it in known }, { null }, { 1L })
        assertEquals("this is", out)
    }

    @Test fun splitsARunOnWithATypoInEachHalf() {
        // Mirrors לארקובלתי → לא קיבלתי: neither half is a word, but each is one edit from one.
        val known = setOf("this", "is")
        val fix = { p: String -> when (p) { "thes" -> "this"; "iss" -> "is"; else -> null } }
        assertEquals("this is", WordPredict.splitCorrection("thesiss", { it in known }, fix, { 1L }))
    }

    @Test fun runOnNeedsBothHalvesToResolve() {
        val known = setOf("this", "is")
        // "thisxq": "this" + "xq" — second half is neither a word nor fixable → no split.
        assertNull(WordPredict.splitCorrection("thisxq", { it in known }, { null }, { 1L }))
    }

    @Test fun runOnRejectsAnEditOnATwoLetterFragment() {
        // A part that needs an edit must be >= 3 long; "ix" (2) can't be fixed to "is" here.
        val known = setOf("this")
        val fix = { p: String -> if (p == "ix") "is" else null }
        assertNull(WordPredict.splitCorrection("thisix", { it in known }, fix, { 1L }))
    }

    // ---- merge-words (inverse of split) ----

    @Test fun mergesAFragmentThatCompletesThePreviousWord() {
        val dict = setOf("together", "another", "keyboard")
        fun merge(prev: String, cur: String) =
            WordPredict.mergeCorrection(prev, cur, { it in dict }, { it in dict })
        assertEquals("together", merge("to", "gether"))   // "gether" isn't a word, "together" is
        assertEquals("another", merge("a", "nother"))
    }

    @Test fun mergeLeavesTwoRealWordsAlone() {
        val dict = setOf("key", "board", "keyboard")
        // "board" is a real word, so "key board" must stay two words, not become "keyboard".
        assertNull(WordPredict.mergeCorrection("key", "board", { it in dict }, { it in dict }))
    }

    // ---- context-correction of a valid word (תודה כבה → תודה רבה) ----

    @Test fun contextNeighborPicksAStronglyPrecededRealWord() {
        // "ted" is a word, but the previous word precedes "red" (one adjacent slip away), not "ted".
        val known = setOf("red", "ted")
        val ctx = mapOf("red" to 5L)
        assertEquals("red" to 5L, WordPredict.bestContextNeighbor("ted", adj, { it in known }, { ctx[it] ?: 0L }))
    }

    @Test fun contextNeighborIsNullWithoutContext() {
        val known = setOf("red", "ted")
        assertNull(WordPredict.bestContextNeighbor("ted", adj, { it in known }, { 0L }))
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

    // ---- next-word ranking (topNext) ----

    private val nextCounts = mapOf("apple" to 5L, "apricot" to 3L, "banana" to 9L, "avocado" to 1L)

    @Test fun topNextRanksByCount() {
        assertEquals(listOf("banana", "apple"), WordPredict.topNext(nextCounts, 2))
    }

    @Test fun topNextFiltersByPrefix() {
        // Only the "a…" words, most-used first; "banana" is excluded by the prefix.
        assertEquals(listOf("apple", "apricot", "avocado"), WordPredict.topNext(nextCounts, 3, "a"))
    }

    @Test fun topNextHonoursLimitAndEmptyCases() {
        assertEquals(listOf("apple"), WordPredict.topNext(nextCounts, 1, "a"))
        assertTrue(WordPredict.topNext(nextCounts, 0).isEmpty())
        assertTrue(WordPredict.topNext(emptyMap(), 3).isEmpty())
        assertTrue(WordPredict.topNext(nextCounts, 3, "z").isEmpty())
    }
}
