package it.sgdc3.smartstorage.network

import it.sgdc3.smartstorage.ServerBacked
import it.sgdc3.smartstorage.storage.ItemType
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order a storage system asks its providers in.
 *
 * This is where both of the duplications that have been found in this addon lived, so the rules get
 * stated one at a time: high priority fills first and empties last, a provider that already holds
 * something gets it before an empty one is opened, and two providers over the same storage are one.
 */
@ServerBacked
class RoutingTest {

    private val cobble = ItemType.of(ItemStack.of(Material.COBBLESTONE))!!
    private val dirt = ItemType.of(ItemStack.of(Material.DIRT))!!

    //<editor-fold desc="ordering and deduplication">

    @Test
    fun `providers come back highest priority first`() {
        val low = FakeProvider("low", priority = 10)
        val high = FakeProvider("high", priority = 90)
        val middle = FakeProvider("middle", priority = 50)

        assertEquals(listOf(high, middle, low), Routing.order(sequenceOf(low, high, middle)))
    }

    /**
     * Not a display nicety. [StorageNetworkGroup.extractableCountOf] sums this list and hands the total
     * to Nova's distributor as a promise, and the distributor gives items to the destination before it
     * asks anyone to produce them — so a chest counted twice is a chest that mints its own contents.
     */
    @Test
    fun `two providers over one piece of storage are one`() {
        val chest = Any()
        val north = FakeProvider("north", priority = 50, storageIdentity = chest)
        val south = FakeProvider("south", priority = 50, storageIdentity = chest)

        assertEquals(listOf(north), Routing.order(sequenceOf(north, south)))
    }

    /**
     * The sort has to happen before the deduplication. A player who reached one chest from two sides of
     * a connector and raised one of them meant the raised one.
     */
    @Test
    fun `the survivor of a duplicate is the one set highest`() {
        val chest = Any()
        val ignored = FakeProvider("ignored", priority = 20, storageIdentity = chest)
        val wanted = FakeProvider("wanted", priority = 80, storageIdentity = chest)

        assertEquals(listOf(wanted), Routing.order(sequenceOf(ignored, wanted)))
    }

    @Test
    fun `distinct storage at the same priority all survives`() {
        val a = FakeProvider("a", priority = 50)
        val b = FakeProvider("b", priority = 50)

        assertEquals(2, Routing.order(sequenceOf(a, b)).size)
    }

    @Test
    fun `no providers is not an error`() {
        assertTrue(Routing.order(emptySequence<StorageProvider>()).isEmpty())
    }

    //</editor-fold>

    //<editor-fold desc="insertion">

    @Test
    fun `insertion reports what would not fit`() {
        val only = FakeProvider("only", capacity = 100L)

        assertEquals(400L, Routing.insert(listOf(only), cobble, 500L))
        assertEquals(100L, only.usedCount)
    }

    @Test
    fun `insertion fills the highest priority first`() {
        val high = FakeProvider("high", priority = 90, capacity = 100L)
        val low = FakeProvider("low", priority = 10, capacity = 1000L)
        val providers = Routing.order(sequenceOf(low, high))

        assertEquals(0L, Routing.insert(providers, cobble, 400L))

        assertEquals(100L, high.usedCount, "the priority storage is filled to the brim")
        assertEquals(300L, low.usedCount, "and the rest spills into the overflow")
    }

    /**
     * The first of the two passes. Without it a wall of barrels sorted by hand would take a stack of
     * cobblestone into whichever barrel happened to be empty rather than the one already holding it.
     */
    @Test
    fun `a provider already holding the type is preferred to an empty one of higher priority`() {
        val empty = FakeProvider("empty", priority = 90, capacity = 1000L)
        val holder = FakeProvider("holder", priority = 10, capacity = 1000L).given(cobble, 5L)
        val providers = Routing.order(sequenceOf(empty, holder))

        Routing.insert(providers, cobble, 100L)

        assertEquals(105L, holder.countOf(cobble))
        assertEquals(0L, empty.usedCount, "the empty one was not opened at all")
    }

    @Test
    fun `what the holder cannot take still spills to the others`() {
        val empty = FakeProvider("empty", priority = 90, capacity = 1000L)
        val holder = FakeProvider("holder", priority = 10, capacity = 10L).given(cobble, 5L)
        val providers = Routing.order(sequenceOf(empty, holder))

        assertEquals(0L, Routing.insert(providers, cobble, 100L))

        assertEquals(10L, holder.countOf(cobble), "filled to its own limit")
        assertEquals(95L, empty.usedCount)
    }

    @Test
    fun `holding a different type does not count as holding this one`() {
        val other = FakeProvider("other", priority = 10, capacity = 1000L).given(dirt, 5L)
        val empty = FakeProvider("empty", priority = 90, capacity = 1000L)
        val providers = Routing.order(sequenceOf(other, empty))

        Routing.insert(providers, cobble, 100L)

        assertEquals(100L, empty.countOf(cobble), "priority decides, because neither holds cobblestone")
    }

    @Test
    fun `insertion stops asking once everything has gone in`() {
        val first = FakeProvider("first", priority = 90, capacity = 1000L)
        val second = FakeProvider("second", priority = 10, capacity = 1000L)
        val providers = Routing.order(sequenceOf(first, second))

        Routing.insert(providers, cobble, 10L)

        assertTrue(second.log.isEmpty(), "a provider that was never needed must not be disturbed")
    }

    @Test
    fun `nowhere to put it gives it all back`() {
        assertEquals(50L, Routing.insert(emptyList(), cobble, 50L))

        val full = FakeProvider("full", capacity = 0L)
        assertEquals(50L, Routing.insert(listOf(full), cobble, 50L))
    }

    //</editor-fold>

    //<editor-fold desc="extraction">

    @Test
    fun `extraction drains the lowest priority first`() {
        val high = FakeProvider("high", priority = 90).given(cobble, 100L)
        val low = FakeProvider("low", priority = 10).given(cobble, 100L)
        val providers = Routing.order(sequenceOf(high, low))

        assertEquals(60L, Routing.extract(providers, cobble, 60L))

        assertEquals(40L, low.countOf(cobble), "the overflow is emptied first")
        assertEquals(100L, high.countOf(cobble), "the priority storage is left alone")
    }

    @Test
    fun `extraction walks on when one provider runs out`() {
        val high = FakeProvider("high", priority = 90).given(cobble, 100L)
        val low = FakeProvider("low", priority = 10).given(cobble, 30L)
        val providers = Routing.order(sequenceOf(high, low))

        assertEquals(80L, Routing.extract(providers, cobble, 80L))

        assertEquals(0L, low.countOf(cobble))
        assertEquals(50L, high.countOf(cobble))
    }

    @Test
    fun `extraction reports what it actually got, not what was asked`() {
        val only = FakeProvider("only").given(cobble, 30L)

        assertEquals(30L, Routing.extract(listOf(only), cobble, 500L))
    }

    /**
     * The shape of the first duplication. A side that will not give up its contents still reports them,
     * because a terminal showing an attached chest is right — so anything that turns a count into a
     * promise has to ask the extractable question, and extraction itself has to agree with it.
     */
    @Test
    fun `storage that refuses to give hands over nothing`() {
        val locked = FakeProvider("locked", writeOnly = true).given(cobble, 500L)

        assertEquals(500L, locked.countOf(cobble), "it still holds it")
        assertEquals(0L, locked.extractableCountOf(cobble), "and still will not promise it")
        assertEquals(0L, Routing.extract(listOf(locked), cobble, 100L))
    }

    @Test
    fun `extraction from nowhere gets nothing`() {
        assertEquals(0L, Routing.extract(emptyList(), cobble, 50L))
        assertEquals(0L, Routing.extract(listOf(FakeProvider("empty")), cobble, 50L))
    }

    @Test
    fun `extraction stops asking once it has enough`() {
        val high = FakeProvider("high", priority = 90).given(cobble, 100L)
        val low = FakeProvider("low", priority = 10).given(cobble, 100L)
        val providers = Routing.order(sequenceOf(high, low))

        Routing.extract(providers, cobble, 10L)

        assertTrue(high.log.isEmpty(), "the priority storage was never even asked")
    }

    //</editor-fold>

    /**
     * A system is not a list of independent stores, and the property that matters most is that moving
     * items around it does not change how many there are.
     */
    @Nested
    @ServerBacked
    inner class Conservation {

        @Test
        fun `what would not fit is reported, and what did comes back out`() {
            val providers = Routing.order(
                sequenceOf(
                    FakeProvider("a", priority = 90, capacity = 70L),
                    FakeProvider("b", priority = 50, capacity = 70L),
                    FakeProvider("c", priority = 10, capacity = 70L)
                )
            )

            // 210 of capacity between them, 250 offered
            val leftOver = Routing.insert(providers, cobble, 250L)
            assertEquals(40L, leftOver)

            assertEquals(210L, Routing.extract(providers, cobble, Long.MAX_VALUE))
            assertEquals(0L, providers.sumOf { it.countOf(cobble) })
        }

        @Test
        fun `a round trip through a mixed system conserves the count`() {
            val providers = Routing.order(
                sequenceOf(
                    FakeProvider("bay", priority = 90, capacity = 100L),
                    FakeProvider("chest", priority = 50, capacity = 100L).given(cobble, 25L),
                    FakeProvider("overflow", priority = 10, capacity = 100L)
                )
            )

            val before = providers.sumOf { it.countOf(cobble) }
            val stored = 150L - Routing.insert(providers, cobble, 150L)
            val taken = Routing.extract(providers, cobble, Long.MAX_VALUE)

            assertEquals(before + stored, taken)
            assertEquals(0L, providers.sumOf { it.countOf(cobble) })
        }

    }

    @Nested
    @ServerBacked
    inner class Fluids {

        @Test
        fun `a fluid goes to a tank already holding it before an empty one`() {
            val empty = FakeFluidProvider("empty", priority = 90)
            val holder = FakeFluidProvider("holder", priority = 10).given(FluidType.WATER, 500L)
            val providers = Routing.order(sequenceOf(empty, holder))

            Routing.insertFluid(providers, FluidType.WATER, 1000L)

            assertEquals(1500L, holder.amountOf(FluidType.WATER))
            assertEquals(0L, empty.usedAmount)
        }

        @Test
        fun `a tank holding the other fluid is not a holder`() {
            val lava = FakeFluidProvider("lava", priority = 10).given(FluidType.LAVA, 500L)
            val empty = FakeFluidProvider("empty", priority = 90)
            val providers = Routing.order(sequenceOf(lava, empty))

            Routing.insertFluid(providers, FluidType.WATER, 1000L)

            assertEquals(1000L, empty.amountOf(FluidType.WATER))
        }

        @Test
        fun `fluid extraction drains the lowest priority first`() {
            val high = FakeFluidProvider("high", priority = 90).given(FluidType.WATER, 1000L)
            val low = FakeFluidProvider("low", priority = 10).given(FluidType.WATER, 1000L)
            val providers = Routing.order(sequenceOf(high, low))

            assertEquals(600L, Routing.extractFluid(providers, FluidType.WATER, 600L))

            assertEquals(400L, low.amountOf(FluidType.WATER))
            assertEquals(1000L, high.amountOf(FluidType.WATER))
        }

        /**
         * The second duplication was here: a tank on a side set to insert only reports its lava, and
         * something turned that report into a promise Nova's fluid distributor then could not collect on.
         */
        @Test
        fun `a tank that refuses to give hands over nothing`() {
            val locked = FakeFluidProvider("locked", writeOnly = true).given(FluidType.LAVA, 4000L)

            assertEquals(4000L, locked.amountOf(FluidType.LAVA))
            assertEquals(0L, locked.extractableAmountOf(FluidType.LAVA))
            assertEquals(0L, Routing.extractFluid(listOf(locked), FluidType.LAVA, 1000L))
        }

    }

}
