package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity checks for the language table ([Languages.ALL]). The layouts and their derived data
 * (adjacency alphabet, long-press hints) are hand-written, so these guard against typos that would
 * otherwise only surface as a broken keyboard for one language on a real device.
 */
class LanguagesTest {

    @Test fun codesAreUniqueAndNonEmpty() {
        val codes = Languages.ALL.map { it.code }
        assertEquals("duplicate language code", codes.size, codes.toSet().size)
        assertTrue(codes.all { it.isNotBlank() })
    }

    @Test fun byCodeResolvesKnownAndFallsBackToEnglish() {
        assertSame(Languages.EN, Languages.byCode("en"))
        assertSame(Languages.HE, Languages.byCode("he"))
        assertSame(Languages.EN, Languages.byCode("zz"))   // unknown → English
    }

    @Test fun everyLanguageHasThreeLetterRows() {
        for (l in Languages.ALL) {
            assertTrue("${l.code}: needs 3 rows", l.rows.size >= 3)
            assertTrue("${l.code}: empty letterRows", l.letterRows.isNotEmpty())
            // letterRows are lowercased; caseless scripts (Hebrew) report isLowerCase()==false, so the
            // right invariant is "letters, none uppercase".
            assertTrue("${l.code}: letterRows must be lowercased letters",
                l.letterRows.all { row -> row.all { it.isLetter() && !it.isUpperCase() } })
        }
    }

    @Test fun autocorrectAlphabetCoversTheLayoutAndHasNoDuplicates() {
        for (l in Languages.ALL) {
            val alpha = l.autocorrectAlphabet
            assertEquals("${l.code}: duplicate letters in alphabet", alpha.length, alpha.toSet().size)
            val layoutLetters = l.letterRows.flatMap { it.toList() }.toSet()
            assertTrue("${l.code}: alphabet missing layout letters",
                alpha.toSet().containsAll(layoutLetters))
        }
    }

    @Test fun onlyEnglishIsBundledTheRestDownload() {
        // Modular dictionaries: only English ships inside the APK; every other language with autocorrect
        // downloads it. No language has both a bundled and a downloadable source.
        for (l in Languages.ALL) {
            assertFalse("${l.code}: can't be both bundled and downloaded",
                l.dictAsset != null && l.dictUrl != null)
        }
        assertNotNull("English should be bundled", Languages.EN.dictAsset)
        assertNull("Hebrew is now downloaded, not bundled", Languages.HE.dictAsset)
        assertNotNull("Hebrew should have a download URL", Languages.HE.dictUrl)
        for (l in listOf(Languages.AR, Languages.ZH)) {
            assertNotNull("${l.code}: should have a downloadable dictionary", l.dictUrl)
        }
    }

    @Test fun voiceModelConfigIsConsistent() {
        // A voice URL and its size hint must agree (both set or both absent). Hebrew has no Vosk model;
        // Arabic is typing-only (no voice).
        for (l in Languages.ALL) {
            assertEquals("${l.code}: voiceUrl and voiceSizeMb must agree",
                l.voiceUrl != null, l.voiceSizeMb > 0)
        }
        assertNull("Hebrew has no Vosk model", Languages.HE.voiceUrl)
        assertNull("Arabic is typing-only", Languages.AR.voiceUrl)
    }

    @Test fun hintKeysAllExistOnTheLayout() {
        for (l in Languages.ALL) {
            val keys = l.rows.flatten().filter { it.length == 1 }.map { it[0] }.toSet()
            for (k in l.hints.keys) {
                assertTrue("${l.code}: hint key '$k' not on layout", k in keys)
            }
        }
    }
}
