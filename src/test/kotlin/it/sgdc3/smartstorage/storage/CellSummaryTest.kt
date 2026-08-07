package it.sgdc3.smartstorage.storage

import it.sgdc3.smartstorage.ServerBacked
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import xyz.xenondevs.cbf.Compound
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The digest a storage cell's tooltip is drawn from.
 *
 * It exists because the tooltip is rebuilt for every clientbound packet carrying the item, for every
 * viewer, and decoding a full 64k cell to print five lines is not something to do at that rate. So it is
 * written beside the real contents and has to agree with them — and has to survive not being there at
 * all, because a cell written before digests existed still has to open.
 */
@ServerBacked
class CellSummaryTest {

    private fun type(material: Material): ItemType = ItemType.of(ItemStack.of(material))!!

    private val cobble get() = type(Material.COBBLESTONE)
    private val dirt get() = type(Material.DIRT)
    private val sand get() = type(Material.SAND)

    @Test
    fun `the digest agrees with the cell it was taken from`() {
        val cell = CellData(8, 1000L)
        cell.insert(cobble, 300L)
        cell.insert(dirt, 20L)

        val summary = CellSummary.fromCompound(cell.toSummary(5))!!

        assertEquals(320L, summary.total)
        assertEquals(2, summary.usedTypes)
        assertEquals(listOf(cobble to 300L, dirt to 20L), summary.preview)
    }

    @Test
    fun `the preview is the biggest entries and stops where it is told`() {
        val cell = CellData(8, 10_000L)
        cell.insert(cobble, 10L)
        cell.insert(dirt, 500L)
        cell.insert(sand, 100L)

        val summary = CellSummary.fromCompound(cell.toSummary(2))!!

        assertEquals(listOf(dirt to 500L, sand to 100L), summary.preview)
        assertEquals(3, summary.usedTypes, "the count is of everything, not of what fit in the preview")
    }

    @Test
    fun `an empty cell has a digest too`() {
        val summary = CellSummary.fromCompound(CellData(8, 1000L).toSummary(5))!!

        assertEquals(0L, summary.total)
        assertEquals(0, summary.usedTypes)
        assertTrue(summary.preview.isEmpty())
    }

    /**
     * The fallback path. A cell stored before digests existed carries none, and the behavior reads the
     * cell itself instead — but only if this says so rather than throwing.
     */
    @Test
    fun `no digest at all reads as no digest`() {
        assertNull(CellSummary.fromCompound(null))
    }

    @Test
    fun `a digest missing its numbers is no digest`() {
        assertNull(CellSummary.fromCompound(Compound()), "a compound with nothing in it")

        val partial = Compound()
        partial["total"] = 100L
        assertNull(CellSummary.fromCompound(partial), "a total with no type count is not enough")
    }

    /**
     * A preview entry naming an item this build no longer has costs a tooltip line. The two totals do
     * not come from the preview, so they stay right.
     */
    @Test
    fun `a preview entry whose item is gone is dropped without taking the rest with it`() {
        val compound = Compound()
        compound["total"] = 500L
        compound["types"] = 2
        compound["previewTypes"] = arrayListOf(ItemStack.of(Material.COBBLESTONE), ItemStack.empty())
        compound["previewAmounts"] = arrayListOf(400L, 100L)

        val summary = CellSummary.fromCompound(compound)

        assertNotNull(summary)
        assertEquals(listOf(cobble to 400L), summary.preview)
        assertEquals(500L, summary.total, "the totals are written separately and stay honest")
        assertEquals(2, summary.usedTypes)
    }

    @Test
    fun `a digest with no preview lists is still a digest`() {
        val compound = Compound()
        compound["total"] = 42L
        compound["types"] = 1

        val summary = CellSummary.fromCompound(compound)

        assertNotNull(summary)
        assertTrue(summary.preview.isEmpty())
        assertEquals(42L, summary.total)
    }

}
