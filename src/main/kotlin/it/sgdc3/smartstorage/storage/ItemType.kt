package it.sgdc3.smartstorage.storage

import org.bukkit.inventory.ItemStack

/**
 * An [ItemStack] reduced to its identity, i.e. everything except the amount.
 *
 * Storage networks track `(type, count)` pairs instead of stacks, which is what allows a single cell
 * to hold more items of one kind than a stack size would ever permit.
 *
 * The wrapped [stack] always has an amount of 1, which makes [ItemStack.equals] equivalent to
 * [ItemStack.isSimilar] and therefore usable as a hash key.
 */
class ItemType private constructor(val stack: ItemStack) {

    /**
     * The vanilla stack size of this type, used to decide how many items a single GUI click hands out.
     */
    val maxStackSize: Int
        get() = stack.maxStackSize

    /**
     * Creates an [ItemStack] of this type holding [amount] items.
     */
    fun createStack(amount: Int): ItemStack =
        stack.clone().apply { this.amount = amount }

    /**
     * Whether [other] is of this type, ignoring its amount. Cheaper than building an [ItemType] for it.
     */
    fun matches(other: ItemStack?): Boolean =
        other != null && !other.isEmpty && stack.isSimilar(other)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ItemType && stack == other.stack)

    override fun hashCode(): Int = stack.hashCode()

    override fun toString(): String = "ItemType(${stack.type})"

    companion object {

        /**
         * Creates an [ItemType] from [stack], or null if [stack] is null or empty.
         */
        fun of(stack: ItemStack?): ItemType? {
            if (stack == null || stack.isEmpty)
                return null

            return ItemType(stack.clone().apply { amount = 1 })
        }

    }

}
