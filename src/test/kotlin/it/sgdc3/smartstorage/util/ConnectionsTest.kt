package it.sgdc3.smartstorage.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [encodeFlags] turns six booleans into the number that names a model file, so its bit order is a
 * contract with `tools/gen-cable-models.ps1` and `tools/gen-hub-models.ps1` rather than an implementation
 * detail. Both walk their arm list in the order north, east, south, west, up, down and write
 * `block/cable/<id>.json` for each combination; getting the order wrong here would not fail to compile
 * or throw, it would quietly point every cable at somebody else's model.
 */
class ConnectionsTest {

    private fun encode(
        north: Boolean = false,
        east: Boolean = false,
        south: Boolean = false,
        west: Boolean = false,
        up: Boolean = false,
        down: Boolean = false
    ) = encodeFlags(north, east, south, west, up, down)

    @Test
    fun `each face owns one bit, least significant first`() {
        assertEquals(1, encode(north = true))
        assertEquals(2, encode(east = true))
        assertEquals(4, encode(south = true))
        assertEquals(8, encode(west = true))
        assertEquals(16, encode(up = true))
        assertEquals(32, encode(down = true))
    }

    @Test
    fun `nothing connected is zero and everything is the last model`() {
        assertEquals(0, encode())
        assertEquals(63, encode(true, true, true, true, true, true))
    }

    @Test
    fun `combinations are the sum of their faces`() {
        assertEquals(3, encode(north = true, east = true))
        assertEquals(48, encode(up = true, down = true))
        assertEquals(5, encode(north = true, south = true))
    }

    @Test
    fun `every combination of six faces has its own id`() {
        val ids = (0..<64).map { mask ->
            encodeFlags(
                mask and 1 != 0,
                mask and 2 != 0,
                mask and 4 != 0,
                mask and 8 != 0,
                mask and 16 != 0,
                mask and 32 != 0
            )
        }

        assertEquals((0..<64).toList(), ids, "the encoding must be the identity on its own bit layout")
    }

    @Test
    fun `no flags at all is zero`() {
        assertEquals(0, encodeFlags())
    }

}
