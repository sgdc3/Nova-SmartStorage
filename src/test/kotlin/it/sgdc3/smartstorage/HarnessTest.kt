package it.sgdc3.smartstorage

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The assumptions every other test rests on, asserted rather than assumed.
 *
 * These look trivial and they are — but the harness runs on a Paper one minor behind production (see
 * [ServerBacked]), and this is the file that would fail first if that gap ever grew into something that
 * matters. A stack of cobblestone that stopped being 64, or a similarity check that started caring about
 * amount, would silently invalidate a great deal of what follows.
 */
@ServerBacked
class HarnessTest {

    @Test
    fun `stack sizes are what the storage maths assumes`() {
        assertEquals(64, ItemStack.of(Material.COBBLESTONE).maxStackSize)
        assertEquals(16, ItemStack.of(Material.SNOWBALL).maxStackSize)
        assertEquals(1, ItemStack.of(Material.DIAMOND_SWORD).maxStackSize)
    }

    @Test
    fun `similarity ignores amount and only amount`() {
        val one = ItemStack.of(Material.COBBLESTONE, 1)
        val many = ItemStack.of(Material.COBBLESTONE, 42)

        assertTrue(one.isSimilar(many), "amount must not make two stacks different")
        assertFalse(one.isSimilar(ItemStack.of(Material.DIRT, 1)))
    }

    @Test
    fun `cloning is deep enough to keep amounts apart`() {
        val original = ItemStack.of(Material.COBBLESTONE, 10)
        val copy = original.clone().apply { amount = 20 }

        assertEquals(10, original.amount, "mutating a clone must not reach the original")
        assertEquals(20, copy.amount)
    }

    @Test
    fun `an empty stack reads as empty`() {
        assertTrue(ItemStack.empty().isEmpty)
        assertFalse(ItemStack.of(Material.COBBLESTONE).isEmpty)
    }

}
