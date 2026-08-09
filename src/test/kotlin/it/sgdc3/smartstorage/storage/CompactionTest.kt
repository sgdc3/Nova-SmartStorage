package it.sgdc3.smartstorage.storage

import it.sgdc3.smartstorage.ServerBacked
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic a compacting barrel runs on.
 *
 * Worth its own tests because it is the only place in this addon that converts between quantities of
 * *different items* — everywhere else a count is a count. Getting a factor wrong here does not throw,
 * it quietly multiplies or divides somebody's iron, which is the failure this suite exists to catch.
 *
 * The ladders are built by hand rather than scanned out of the server: what is under test is the sums,
 * and a test that also depended on the recipe list would fail for two unrelated reasons.
 */
@ServerBacked
class CompactionTest {

    private fun type(material: Material): ItemType = ItemType.of(ItemStack.of(material))!!

    private val nugget get() = type(Material.IRON_NUGGET)
    private val ingot get() = type(Material.IRON_INGOT)
    private val block get() = type(Material.IRON_BLOCK)

    /** The three-rung ladder the whole feature was asked for. */
    private val iron get() = Compaction(listOf(nugget, ingot, block), listOf(9, 9))

    /** Two rungs, four to one — a dyed-block sort of ladder. */
    private val short get() = Compaction(listOf(type(Material.QUARTZ), type(Material.QUARTZ_BLOCK)), listOf(4))

    @Test
    fun `the top of the ladder is what a barrel would store`() {
        assertEquals(block, iron.top)
        assertEquals(type(Material.QUARTZ_BLOCK), short.top)
    }

    @Test
    fun `a tier is worth the product of every step below it`() {
        assertEquals(1L, iron.unitsOf(nugget))
        assertEquals(9L, iron.unitsOf(ingot))
        assertEquals(81L, iron.unitsOf(block))
    }

    @Test
    fun `an item from another ladder is worth nothing`() {
        assertEquals(0L, iron.unitsOf(type(Material.GOLD_INGOT)))
    }

    @Test
    fun `a remainder comes back as the largest pieces that hold it`() {
        // forty nuggets is four ingots and four nuggets, not forty nuggets
        val pieces = iron.split(40L)

        assertEquals(2, pieces.size)
        assertEquals(Material.IRON_INGOT, pieces[0].type)
        assertEquals(4, pieces[0].amount)
        assertEquals(Material.IRON_NUGGET, pieces[1].type)
        assertEquals(4, pieces[1].amount)
    }

    @Test
    fun `a remainder that reaches the top comes back as the top`() {
        // 81 nuggets is exactly one block, and handing back nine ingots instead would be a worse answer
        val pieces = iron.split(81L)

        assertEquals(1, pieces.size)
        assertEquals(Material.IRON_BLOCK, pieces[0].type)
        assertEquals(1, pieces[0].amount)
    }

    @Test
    fun `one block is also nine ingots and eighty-one nuggets`() {
        // the readout a player wants: the same iron at every density, not a decomposition of it
        val tiers = iron.atEachTier(81L)

        assertEquals(3, tiers.size)
        assertEquals(block to 1L, tiers[0])
        assertEquals(ingot to 9L, tiers[1])
        assertEquals(nugget to 81L, tiers[2])
    }

    @Test
    fun `a tier that does not reach one is left out`() {
        // forty nuggets are no block at all, four ingots, and forty nuggets
        val tiers = iron.atEachTier(40L)

        assertEquals(2, tiers.size)
        assertEquals(ingot to 4L, tiers[0])
        assertEquals(nugget to 40L, tiers[1])
    }

    @Test
    fun `every tier is the same holding, so none of them may be added up`() {
        // the invariant the network exposure rests on: each line is the whole barrel, priced in that
        // tier — so a total taken across them would count the iron three times
        val units = 1234L
        for ((tier, count) in iron.atEachTier(units))
            assertEquals(units / iron.unitsOf(tier), count, "$tier disagreed about how much is there")
    }

    @Test
    fun `nothing splits into nothing`() {
        assertTrue(iron.split(0L).isEmpty())
    }

    @Test
    fun `a split never loses or invents a unit`() {
        // the property that matters: whatever goes in comes back out, whichever pieces it takes
        for (units in longArrayOf(1, 8, 9, 10, 80, 81, 82, 728, 729, 5000)) {
            val back = iron.split(units).sumOf { stack ->
                iron.unitsOf(ItemType.of(stack)!!) * stack.amount
            }
            assertEquals(units, back, "$units units did not survive the round trip")
        }
    }

    @Test
    fun `pieces are handed over in whole stacks`() {
        // 5000 nuggets is 61 blocks and change, and 61 blocks is more than one stack of them
        val pieces = iron.split(5000L)

        assertTrue(pieces.all { it.amount <= it.maxStackSize }, "a stack came back over its own limit")
        assertTrue(pieces.all { it.amount > 0 }, "an empty stack came back")
    }

    @Test
    fun `a two rung ladder counts in fours`() {
        assertEquals(1L, short.unitsOf(type(Material.QUARTZ)))
        assertEquals(4L, short.unitsOf(type(Material.QUARTZ_BLOCK)))

        val pieces = short.split(7L)
        assertEquals(Material.QUARTZ_BLOCK, pieces[0].type)
        assertEquals(1, pieces[0].amount)
        assertEquals(Material.QUARTZ, pieces[1].type)
        assertEquals(3, pieces[1].amount)
    }

}
