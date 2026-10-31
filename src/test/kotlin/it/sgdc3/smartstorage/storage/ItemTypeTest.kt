package it.sgdc3.smartstorage.storage

import it.sgdc3.smartstorage.ServerBacked
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.bukkit.inventory.ItemType as BukkitItemType

/**
 * [ItemType] is the key of every map in this addon, so it has to behave like one: two of them that mean
 * the same item must be equal, hash alike, and stay that way no matter what anybody does to the stacks
 * they came from.
 */
@ServerBacked
class ItemTypeTest {

    private fun named(item: BukkitItemType, name: String): ItemStack =
        item.createItemStack().apply { editMeta { it.displayName(Component.text(name)) } }

    //<editor-fold desc="construction">

    @Test
    fun `nothing is not a type`() {
        assertNull(ItemType.of(null))
        assertNull(ItemType.of(ItemStack.empty()))
        assertNull(ItemType.of(BukkitItemType.AIR.createItemStack()))
    }

    @Test
    fun `the amount is dropped, whatever it was`() {
        assertEquals(1, ItemType.of(BukkitItemType.COBBLESTONE.createItemStack(64))!!.stack.amount)
        assertEquals(1, ItemType.of(BukkitItemType.COBBLESTONE.createItemStack(1))!!.stack.amount)
    }

    /**
     * The type has to own its stack. If it merely pointed at the caller's, an item sitting in a chest
     * that somebody then modified would silently become a different key than the one it was filed under
     * — and a cell's contents would develop entries nothing can ever look up again.
     */
    @Test
    fun `a type does not alias the stack it was made from`() {
        val source = BukkitItemType.COBBLESTONE.createItemStack(5)
        val type = ItemType.of(source)!!
        val before = ItemType.of(source)!!

        source.amount = 30
        source.editMeta { it.displayName(Component.text("renamed after the fact")) }

        assertEquals(1, type.stack.amount)
        assertEquals(before, type, "a component added to the source must not reach the type")
        assertNotEquals(type, ItemType.of(source), "the source really did change")
    }

    //</editor-fold>

    //<editor-fold desc="identity">

    @Test
    fun `amount does not make two types different`() {
        val few = ItemType.of(BukkitItemType.COBBLESTONE.createItemStack(1))!!
        val many = ItemType.of(BukkitItemType.COBBLESTONE.createItemStack(64))!!

        assertEquals(few, many)
        assertEquals(few.hashCode(), many.hashCode())
    }

    @Test
    fun `material does`() {
        assertNotEquals(
            ItemType.of(BukkitItemType.COBBLESTONE.createItemStack()),
            ItemType.of(BukkitItemType.DIRT.createItemStack())
        )
    }

    /**
     * The whole reason a cell has a *type* limit: two differently named copies of one material are two
     * entries, not one. A player renaming half their iron blocks is filling the cell twice as fast, and
     * the cell has to notice.
     */
    @Test
    fun `components do`() {
        val plain = ItemType.of(BukkitItemType.IRON_BLOCK.createItemStack())!!
        val labelled = ItemType.of(named(BukkitItemType.IRON_BLOCK, "Reserve"))!!
        val labelledDifferently = ItemType.of(named(BukkitItemType.IRON_BLOCK, "Spare"))!!

        assertNotEquals(plain, labelled)
        assertNotEquals(labelled, labelledDifferently)
        assertEquals(labelled, ItemType.of(named(BukkitItemType.IRON_BLOCK, "Reserve")))
    }

    @Test
    fun `equal types collapse to one map key`() {
        val map = HashMap<ItemType, Long>()
        map.merge(ItemType.of(BukkitItemType.COBBLESTONE.createItemStack(1))!!, 10L, Long::plus)
        map.merge(ItemType.of(BukkitItemType.COBBLESTONE.createItemStack(64))!!, 5L, Long::plus)

        assertEquals(1, map.size)
        assertEquals(15L, map.values.single())
    }

    @Test
    fun `a type equals itself`() {
        val type = ItemType.of(BukkitItemType.COBBLESTONE.createItemStack())!!
        assertSame(type, type)
        assertEquals(type, type)
        assertFalse(type.equals(null))
        assertFalse(type.equals("cobblestone"))
    }

    //</editor-fold>

    //<editor-fold desc="handing items back out">

    @Test
    fun `createStack hands out the requested amount`() {
        val type = ItemType.of(BukkitItemType.COBBLESTONE.createItemStack())!!

        assertEquals(1, type.createStack(1).amount)
        assertEquals(64, type.createStack(64).amount)
        // above a stack, because the barrel and the cell both hold more than one and split later
        assertEquals(200, type.createStack(200).amount)
    }

    @Test
    fun `createStack does not hand out the same stack twice`() {
        val type = ItemType.of(BukkitItemType.COBBLESTONE.createItemStack())!!
        val first = type.createStack(10)
        val second = type.createStack(10)

        first.amount = 99

        assertEquals(10, second.amount, "two stacks from one type must be independent")
        assertEquals(1, type.stack.amount, "handing one out must not disturb the type itself")
    }

    @Test
    fun `createStack keeps the components`() {
        val type = ItemType.of(named(BukkitItemType.IRON_BLOCK, "Reserve"))!!
        assertEquals(type, ItemType.of(type.createStack(7)))
    }

    //</editor-fold>

    //<editor-fold desc="matches">

    @Test
    fun `matches is similarity, not equality`() {
        val type = ItemType.of(BukkitItemType.COBBLESTONE.createItemStack())!!

        assertTrue(type.matches(BukkitItemType.COBBLESTONE.createItemStack(1)))
        assertTrue(type.matches(BukkitItemType.COBBLESTONE.createItemStack(64)))
        assertFalse(type.matches(BukkitItemType.DIRT.createItemStack()))
    }

    @Test
    fun `matches survives nothing at all`() {
        val type = ItemType.of(BukkitItemType.COBBLESTONE.createItemStack())!!

        assertFalse(type.matches(null))
        assertFalse(type.matches(ItemStack.empty()))
    }

    @Test
    fun `maxStackSize is the item's own`() {
        assertEquals(64, ItemType.of(BukkitItemType.COBBLESTONE.createItemStack())!!.maxStackSize)
        assertEquals(16, ItemType.of(BukkitItemType.SNOWBALL.createItemStack())!!.maxStackSize)
        assertEquals(1, ItemType.of(BukkitItemType.DIAMOND_SWORD.createItemStack())!!.maxStackSize)
    }

    //</editor-fold>

}
