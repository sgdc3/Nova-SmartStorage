package it.sgdc3.smartstorage.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [abbreviate] is what a barrel writes on its front, where there is room for about four characters.
 *
 * Tested because both of its rules are the kind that look obviously right and are not: the threshold
 * between a decimal and none, and the direction it is wrong in. A display that rounds up says a barrel
 * holds material it does not have.
 */
class CountsTest {

    @Test
    fun `anything under a thousand is written out`() {
        assertEquals("0", abbreviate(0L))
        assertEquals("1", abbreviate(1L))
        assertEquals("999", abbreviate(999L))
    }

    @Test
    fun `thousands take a K and one decimal`() {
        assertEquals("1K", abbreviate(1000L))
        assertEquals("1.2K", abbreviate(1234L))
        assertEquals("9.9K", abbreviate(9999L))
    }

    @Test
    fun `ten thousand and up drops the decimal, because four characters is the whole budget`() {
        assertEquals("10K", abbreviate(10_000L))
        assertEquals("18K", abbreviate(18_432L))
        assertEquals("999K", abbreviate(999_999L))
    }

    @Test
    fun `millions take an M`() {
        assertEquals("1.3M", abbreviate(1_327_104L))
        assertEquals("16M", abbreviate(16_777_216L))
    }

    @Test
    fun `a whole number of units carries no decimal`() {
        // 2.0K reads as a rounded figure when it is an exact one
        assertEquals("2K", abbreviate(2000L))
        assertEquals("3M", abbreviate(3_000_000L))
    }

    @Test
    fun `it truncates, so it never claims more than is there`() {
        // 1999 nuggets are not two thousand nuggets, and a barrel front must not say they are
        assertEquals("1.9K", abbreviate(1999L))
        assertEquals("99K", abbreviate(99_999L))
        assertEquals("1M", abbreviate(1_099_999L))
    }

    @Test
    fun `nothing it prints outruns the space it was written for`() {
        // Up to a billion, which is the range it claims and comfortably more than this addon can hold:
        // a barrel filled with the smallest rung of a ladder is about 1.3 million, a wall of them under
        // a hundred million. Past a billion it grows rather than lying, which is the right way round.
        for (count in longArrayOf(0, 1, 999, 1000, 1234, 9999, 10_000, 999_999, 1_327_104, 85_000_000, 999_999_999))
            assertTrue(abbreviate(count).length <= 4, "$count came back as ${abbreviate(count)}")
    }

    @Test
    fun `a negative reads the same way with a sign on it`() {
        // nothing hands it one today, but a formatter that mangles them is a trap for whatever does
        assertEquals("-1.2K", abbreviate(-1234L))
        assertEquals("-18K", abbreviate(-18_432L))
        assertEquals("-999", abbreviate(-999L))
    }

}
