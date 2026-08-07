package it.sgdc3.smartstorage.storage

import it.sgdc3.smartstorage.ServerBacked
import org.junit.jupiter.api.Test
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fluid cell is bounded on one axis rather than two — Nova has exactly two fluids, so a limit on how
 * many of them a cell may hold would be a knob with one setting. What that leaves is a single capacity
 * shared between them, which is the whole of the interesting behaviour.
 *
 * Amounts are Nova's fluid units throughout: 1000 to the bucket.
 */
@ServerBacked
class FluidCellDataTest {

    private fun cell(maxAmount: Long = 16_000L) = FluidCellData(maxAmount)

    @Test
    fun `a fresh cell is empty`() {
        val cell = cell()

        assertTrue(cell.isEmpty)
        assertEquals(0L, cell.total)
        assertEquals(0L, cell.amountOf(FluidType.WATER))
    }

    @Test
    fun `inserting reports what it stored`() {
        val cell = cell()

        assertEquals(1000L, cell.insert(FluidType.WATER, 1000L))
        assertEquals(1000L, cell.amountOf(FluidType.WATER))
    }

    /**
     * The one rule that makes a fluid cell different from a storage cell: four buckets of lava are four
     * buckets of water the cell can no longer take.
     */
    @Test
    fun `the capacity is shared between the fluids`() {
        val cell = cell(maxAmount = 10_000L)

        assertEquals(4000L, cell.insert(FluidType.LAVA, 4000L))
        assertEquals(6000L, cell.insert(FluidType.WATER, 9000L), "only what the lava left")
        assertEquals(10_000L, cell.total)
        assertEquals(4000L, cell.amountOf(FluidType.LAVA), "and the lava is untouched")
    }

    @Test
    fun `a full cell takes nothing of either kind`() {
        val cell = cell(maxAmount = 1000L)
        cell.insert(FluidType.WATER, 1000L)

        assertEquals(0L, cell.insert(FluidType.WATER, 1L))
        assertEquals(0L, cell.insert(FluidType.LAVA, 1L))
    }

    @Test
    fun `extracting the last drop removes the entry`() {
        val cell = cell()
        cell.insert(FluidType.WATER, 500L)
        cell.insert(FluidType.LAVA, 500L)

        assertEquals(500L, cell.extract(FluidType.WATER, 500L))
        assertEquals(0L, cell.amountOf(FluidType.WATER))
        assertEquals(listOf(FluidType.LAVA to 500L), cell.entries())
    }

    @Test
    fun `asking for more than there is gets what there is`() {
        val cell = cell()
        cell.insert(FluidType.WATER, 300L)

        assertEquals(300L, cell.extract(FluidType.WATER, 999_999L))
        assertTrue(cell.isEmpty)
    }

    @Test
    fun `extracting a fluid the cell does not hold does nothing`() {
        val cell = cell()
        cell.insert(FluidType.WATER, 300L)

        assertEquals(0L, cell.extract(FluidType.LAVA, 100L))
        assertEquals(300L, cell.total)
    }

    @Test
    fun `zero and negative are refused rather than acted on`() {
        val cell = cell()
        cell.insert(FluidType.WATER, 500L)

        assertEquals(0L, cell.insert(FluidType.WATER, 0L))
        assertEquals(0L, cell.insert(FluidType.WATER, -100L))
        assertEquals(0L, cell.extract(FluidType.WATER, 0L))
        assertEquals(0L, cell.extract(FluidType.WATER, -100L))
        assertEquals(500L, cell.total)
    }

    @Test
    fun `collectInto adds to what is already in the map`() {
        val cell = cell()
        cell.insert(FluidType.WATER, 100L)

        val index = HashMap<FluidType, Long>()
        index[FluidType.WATER] = 900L
        cell.collectInto(index)

        assertEquals(1000L, index[FluidType.WATER])
    }

    //<editor-fold desc="serialisation", defaultstate="collapsed">

    @Test
    fun `contents survive a round trip`() {
        val cell = cell()
        cell.insert(FluidType.WATER, 1500L)
        cell.insert(FluidType.LAVA, 250L)

        val restored = FluidCellData.fromCompound(cell.toCompound(), 16_000L)

        assertEquals(1500L, restored.amountOf(FluidType.WATER))
        assertEquals(250L, restored.amountOf(FluidType.LAVA))
        assertEquals(1750L, restored.total)
    }

    @Test
    fun `nothing at all reads as an empty cell`() {
        assertTrue(FluidCellData.fromCompound(null, 16_000L).isEmpty)
    }

    /**
     * Fluids are written by *name*, not by ordinal, precisely so that a build which reordered or removed
     * one does not silently turn everybody's lava into water. An entry this build cannot name is dropped.
     */
    @Test
    fun `a fluid this build does not know is dropped rather than guessed at`() {
        val compound = Compound()
        compound["types"] = arrayListOf("WATER", "AETHER", "LAVA")
        compound["amounts"] = arrayListOf(100L, 500L, 200L)

        val restored = FluidCellData.fromCompound(compound, 16_000L)

        assertEquals(100L, restored.amountOf(FluidType.WATER))
        assertEquals(200L, restored.amountOf(FluidType.LAVA), "the entries around it are unaffected")
        assertEquals(300L, restored.total, "and the unknown one contributes nothing")
    }

    @Test
    fun `an entry with no amount is dropped`() {
        val compound = Compound()
        compound["types"] = arrayListOf("WATER", "LAVA")
        compound["amounts"] = arrayListOf(0L, 200L)

        val restored = FluidCellData.fromCompound(compound, 16_000L)

        assertEquals(0L, restored.amountOf(FluidType.WATER))
        assertEquals(200L, restored.total)
    }

    /**
     * The two lists are written together and should stay the same length, so a mismatch means the data
     * is damaged. Reading the shorter of the two is what keeps that from being an exception on load —
     * which for a tile entity means a chunk that will not come back.
     */
    @Test
    fun `mismatched lists are read as far as they agree`() {
        val compound = Compound()
        compound["types"] = arrayListOf("WATER", "LAVA")
        compound["amounts"] = arrayListOf(100L)

        val restored = FluidCellData.fromCompound(compound, 16_000L)

        assertEquals(100L, restored.total)
        assertEquals(100L, restored.amountOf(FluidType.WATER))
    }

    //</editor-fold>

}
