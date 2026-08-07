package it.sgdc3.smartstorage.storage

import it.sgdc3.smartstorage.ServerBacked
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A storage cell is bounded on two axes that do not interact — how many *kinds* of thing it holds and
 * how many things — and nearly every interesting case here is one of them being reached while the other
 * has room to spare.
 */
@ServerBacked
class CellDataTest {

    private fun type(material: Material): ItemType =
        ItemType.of(ItemStack.of(material))!!

    private val cobble get() = type(Material.COBBLESTONE)
    private val dirt get() = type(Material.DIRT)
    private val sand get() = type(Material.SAND)

    private fun cell(maxTypes: Int = 8, maxItems: Long = 1000L) = CellData(maxTypes, maxItems)

    //<editor-fold desc="the item limit">

    @Test
    fun `a fresh cell is empty`() {
        val cell = cell()

        assertTrue(cell.isEmpty)
        assertEquals(0, cell.usedTypes)
        assertEquals(0L, cell.total)
        assertEquals(0L, cell.countOf(cobble))
    }

    @Test
    fun `inserting reports what it stored`() {
        val cell = cell()

        assertEquals(100L, cell.insert(cobble, 100L))
        assertEquals(100L, cell.total)
        assertEquals(100L, cell.countOf(cobble))
    }

    @Test
    fun `an insert that does not fit stores what does and says so`() {
        val cell = cell(maxItems = 100L)
        cell.insert(cobble, 90L)

        assertEquals(10L, cell.insert(cobble, 50L), "only the room that was left")
        assertEquals(100L, cell.total)
    }

    @Test
    fun `a full cell takes nothing`() {
        val cell = cell(maxItems = 100L)
        cell.insert(cobble, 100L)

        assertEquals(0L, cell.insert(cobble, 1L))
        assertEquals(0L, cell.insert(dirt, 1L), "full is full, whatever the type")
        assertEquals(100L, cell.total)
    }

    @Test
    fun `the item limit is shared across types`() {
        val cell = cell(maxItems = 100L)

        assertEquals(60L, cell.insert(cobble, 60L))
        assertEquals(40L, cell.insert(dirt, 60L), "what one type takes, another cannot")
        assertEquals(100L, cell.total)
    }

    @Test
    fun `a cell with no room for anything takes nothing`() {
        val cell = cell(maxItems = 0L)

        assertEquals(0L, cell.insert(cobble, 1L))
        assertTrue(cell.isEmpty)
    }

    //</editor-fold>

    //<editor-fold desc="the type limit">

    @Test
    fun `a new type is refused once the type limit is reached`() {
        val cell = cell(maxTypes = 2, maxItems = 1000L)
        cell.insert(cobble, 1L)
        cell.insert(dirt, 1L)

        assertEquals(0L, cell.insert(sand, 1L))
        assertEquals(2, cell.usedTypes)
        assertEquals(2L, cell.total, "and nothing was stored under some other key either")
    }

    /**
     * The limit is on *kinds*, not on entries, so a cell that is full of types still fills up with more
     * of the types it already has. Anything else would strand the item capacity a player paid for.
     */
    @Test
    fun `a known type is still accepted once the type limit is reached`() {
        val cell = cell(maxTypes = 1, maxItems = 1000L)
        cell.insert(cobble, 1L)

        assertEquals(500L, cell.insert(cobble, 500L))
        assertEquals(501L, cell.countOf(cobble))
    }

    @Test
    fun `a cell with no room for any type takes nothing`() {
        val cell = cell(maxTypes = 0, maxItems = 1000L)

        assertEquals(0L, cell.insert(cobble, 1L))
        assertTrue(cell.isEmpty)
    }

    /**
     * Emptying a type frees its slot — otherwise a cell that had once seen eight things could never be
     * repurposed without being thrown away.
     */
    @Test
    fun `emptying a type gives its slot back`() {
        val cell = cell(maxTypes = 1, maxItems = 1000L)
        cell.insert(cobble, 10L)
        cell.extract(cobble, 10L)

        assertEquals(0, cell.usedTypes)
        assertEquals(5L, cell.insert(dirt, 5L))
    }

    //</editor-fold>

    //<editor-fold desc="extraction">

    @Test
    fun `extracting reports what it removed`() {
        val cell = cell()
        cell.insert(cobble, 100L)

        assertEquals(30L, cell.extract(cobble, 30L))
        assertEquals(70L, cell.total)
        assertEquals(70L, cell.countOf(cobble))
    }

    @Test
    fun `asking for more than there is gets what there is`() {
        val cell = cell()
        cell.insert(cobble, 10L)

        assertEquals(10L, cell.extract(cobble, 1000L))
        assertEquals(0L, cell.total)
        assertTrue(cell.isEmpty)
    }

    @Test
    fun `extracting the last item removes the entry rather than leaving a zero`() {
        val cell = cell()
        cell.insert(cobble, 10L)
        cell.insert(dirt, 10L)
        cell.extract(cobble, 10L)

        assertEquals(1, cell.usedTypes, "a zero entry would still occupy a type slot")
        assertEquals(0L, cell.countOf(cobble))
    }

    @Test
    fun `extracting what was never there does nothing`() {
        val cell = cell()
        cell.insert(cobble, 10L)

        assertEquals(0L, cell.extract(dirt, 5L))
        assertEquals(10L, cell.total)
    }

    @Test
    fun `extracting from an empty cell does nothing`() {
        assertEquals(0L, cell().extract(cobble, 5L))
    }

    //</editor-fold>

    //<editor-fold desc="amounts that are not amounts">

    @Test
    fun `zero and negative are refused rather than acted on`() {
        val cell = cell()
        cell.insert(cobble, 50L)

        assertEquals(0L, cell.insert(cobble, 0L))
        assertEquals(0L, cell.insert(cobble, -10L))
        assertEquals(0L, cell.extract(cobble, 0L))
        assertEquals(0L, cell.extract(cobble, -10L))
        assertEquals(50L, cell.total, "a negative insert must not become an extraction, or the reverse")
    }

    //</editor-fold>

    //<editor-fold desc="reading the contents">

    @Test
    fun `collectInto adds to what is already in the map`() {
        val cell = cell()
        cell.insert(cobble, 10L)
        cell.insert(dirt, 5L)

        val index = HashMap<ItemType, Long>()
        index[cobble] = 100L
        cell.collectInto(index)

        assertEquals(110L, index[cobble], "a second cell holding the same type must add, not replace")
        assertEquals(5L, index[dirt])
    }

    @Test
    fun `preview shows the biggest entries first and no more than asked`() {
        val cell = cell()
        cell.insert(cobble, 10L)
        cell.insert(dirt, 300L)
        cell.insert(sand, 50L)

        assertEquals(listOf(dirt to 300L, sand to 50L), cell.preview(2))
        assertEquals(3, cell.preview(10).size, "asking for more than there is is not an error")
    }

    //</editor-fold>

    //<editor-fold desc="a cell whose tier shrank under it">

    /**
     * Config is editable, so a cell can be loaded by a build whose limits are lower than the ones it was
     * filled under. It must not throw, must not lose anything, and must not pretend it has room.
     */
    @Test
    fun `a cell over its item limit refuses more but keeps what it has`() {
        val overfull = CellData(8, 100L, linkedMapOf(cobble to 500L))

        assertEquals(500L, overfull.total)
        assertEquals(0L, overfull.insert(cobble, 1L))
        assertEquals(500L, overfull.extract(cobble, 1000L), "everything must still come back out")
    }

    @Test
    fun `a cell over its type limit refuses new types but keeps the old ones`() {
        val overfull = CellData(1, 1000L, linkedMapOf(cobble to 10L, dirt to 10L))

        assertEquals(2, overfull.usedTypes)
        assertEquals(0L, overfull.insert(sand, 1L))
        assertEquals(5L, overfull.insert(cobble, 5L), "the types it already has still work")
    }

    //</editor-fold>

    @Nested
    @ServerBacked
    inner class Serialisation {

        @Test
        fun `contents survive a round trip`() {
            val cell = cell()
            cell.insert(cobble, 100L)
            cell.insert(dirt, 7L)

            val restored = CellData.fromCompound(cell.toCompound(), 8, 1000L)

            assertEquals(2, restored.usedTypes)
            assertEquals(107L, restored.total)
            assertEquals(100L, restored.countOf(cobble))
            assertEquals(7L, restored.countOf(dirt))
        }

        @Test
        fun `an empty cell survives a round trip`() {
            val restored = CellData.fromCompound(cell().toCompound(), 8, 1000L)

            assertTrue(restored.isEmpty)
            assertEquals(0L, restored.total)
        }

        @Test
        fun `nothing at all reads as an empty cell`() {
            val restored = CellData.fromCompound(null, 8, 1000L)

            assertTrue(restored.isEmpty)
            assertFalse(restored.maxItems == 0L, "the limits still come from the tier, not the data")
        }

        @Test
        fun `the total is recomputed from the contents rather than trusted`() {
            val restored = CellData.fromCompound(
                CellData(8, 1000L, linkedMapOf(cobble to 40L, dirt to 2L)).toCompound(),
                8,
                1000L
            )

            assertEquals(42L, restored.total)
        }

        @Test
        fun `the limits come from the tier the cell is read as, not from the data`() {
            val cell = cell(maxTypes = 8, maxItems = 1000L)
            cell.insert(cobble, 900L)

            val asSmallerTier = CellData.fromCompound(cell.toCompound(), 2, 100L)

            assertEquals(900L, asSmallerTier.total, "contents are not truncated on read")
            assertEquals(0L, asSmallerTier.insert(cobble, 1L), "but the new limit binds from now on")
        }

    }

}
