package it.sgdc3.smartstorage

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The assumptions every other test rests on, asserted rather than assumed.
 *
 * These look trivial and they are — but the harness is MockBukkit rather than Paper (see
 * [ServerBacked]), and this is the file that would fail first if the two ever disagreed about something
 * that matters. A stack of cobblestone that stopped being 64, or a similarity check that started caring about
 * amount, would silently invalidate a great deal of what follows.
 */
@ServerBacked
class HarnessTest {

    @Test
    fun `stack sizes are what the storage maths assumes`() {
        assertEquals(64, ItemType.COBBLESTONE.createItemStack().maxStackSize)
        assertEquals(16, ItemType.SNOWBALL.createItemStack().maxStackSize)
        assertEquals(1, ItemType.DIAMOND_SWORD.createItemStack().maxStackSize)
    }

    @Test
    fun `similarity ignores amount and only amount`() {
        val one = ItemType.COBBLESTONE.createItemStack(1)
        val many = ItemType.COBBLESTONE.createItemStack(42)

        assertTrue(one.isSimilar(many), "amount must not make two stacks different")
        assertFalse(one.isSimilar(ItemType.DIRT.createItemStack(1)))
    }

    @Test
    fun `cloning is deep enough to keep amounts apart`() {
        val original = ItemType.COBBLESTONE.createItemStack(10)
        val copy = original.clone().apply { amount = 20 }

        assertEquals(10, original.amount, "mutating a clone must not reach the original")
        assertEquals(20, copy.amount)
    }

    @Test
    fun `an empty stack reads as empty`() {
        assertTrue(ItemStack.empty().isEmpty)
        assertFalse(ItemType.COBBLESTONE.createItemStack().isEmpty)
    }

}
