package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for [TextOps] — the word-boundary, autocorrect-casing, grapheme-deletion and Hebrew
 * final-form rules the keyboard depends on. These are easy to get subtly wrong, so they're pinned here.
 */
class TextOpsTest {

    // ---- applyCase ----

    @Test fun lowercaseStaysLowercase() {
        assertEquals("the", TextOps.applyCase("teh", "the"))
    }

    @Test fun leadingCapitalIsKept() {
        assertEquals("The", TextOps.applyCase("Teh", "the"))
        assertEquals("Apple", TextOps.applyCase("Aple", "apple"))
    }

    @Test fun allCapsStaysAllCaps() {
        assertEquals("THE", TextOps.applyCase("TEH", "the"))
    }

    @Test fun singleLetterCapitalCapitalises() {
        // A lone capital ("I") isn't "ALL CAPS" (length 1), so it takes the leading-capital branch.
        assertEquals("I", TextOps.applyCase("I", "i"))
    }

    // ---- isWordChar / isCorrectTrigger ----

    @Test fun wordCharsAreLettersAndApostrophe() {
        assertTrue(TextOps.isWordChar('a'))
        assertTrue(TextOps.isWordChar('Z'))
        assertTrue(TextOps.isWordChar('\''))
        assertTrue(TextOps.isWordChar('é'))
        assertFalse(TextOps.isWordChar(' '))
        assertFalse(TextOps.isWordChar('5'))
        assertFalse(TextOps.isWordChar('.'))
    }

    @Test fun correctTriggersAreWhitespaceAndSentencePunctuation() {
        for (c in listOf(' ', '\n', '.', ',', '!', '?', ';', ':', ')')) {
            assertTrue("'$c' should trigger", TextOps.isCorrectTrigger(c))
        }
        for (c in listOf('a', '(', '\'', '-')) {
            assertFalse("'$c' should not trigger", TextOps.isCorrectTrigger(c))
        }
    }

    // ---- trailingWord ----

    @Test fun trailingWordTakesTheLastRun() {
        assertEquals("world", TextOps.trailingWord("hello world"))
        assertEquals("bar", TextOps.trailingWord("foo, bar"))
        assertEquals("don't", TextOps.trailingWord("don't"))
    }

    @Test fun trailingWordIsEmptyAfterATerminator() {
        assertEquals("", TextOps.trailingWord("hello "))
        assertEquals("", TextOps.trailingWord(""))
    }

    // ---- lastGraphemeLength ----

    @Test fun graphemeLengthIsOneForPlainText() {
        assertEquals(1, TextOps.lastGraphemeLength("a"))
        assertEquals(1, TextOps.lastGraphemeLength("ab"))
    }

    @Test fun graphemeLengthHandlesNullAndEmpty() {
        assertEquals(1, TextOps.lastGraphemeLength(null))
        assertEquals(1, TextOps.lastGraphemeLength(""))
    }

    @Test fun graphemeLengthCoversAFullEmoji() {
        // 😀 is a surrogate pair (2 UTF-16 units) but one grapheme — backspace must delete both.
        assertEquals(2, TextOps.lastGraphemeLength("😀"))
        assertEquals(2, TextOps.lastGraphemeLength("hi 😀"))
    }

    // ---- Hebrew final forms ----

    @Test fun hebrewFinalFormMapsAreExactInverses() {
        val m2f = TextOps.hebrewMedialToFinal
        val f2m = TextOps.hebrewFinalToMedial
        assertEquals(m2f.size, f2m.size)
        for ((medial, final) in m2f) {
            assertEquals("round-trip $medial", medial, f2m[final])
        }
        // No letter is both a medial and a final key (the two alphabets are disjoint).
        assertTrue((m2f.keys intersect f2m.keys).isEmpty())
    }
}
