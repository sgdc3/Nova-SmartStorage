package it.sgdc3.smartstorage.gui

import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.storage.ItemType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.commons.provider.MutableProvider
import xyz.xenondevs.commons.provider.mutableProvider
import xyz.xenondevs.invui.dsl.item
import xyz.xenondevs.invui.inventory.Inventory
import xyz.xenondevs.invui.inventory.event.UpdateReason
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.nova.util.addItemCorrectly
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.playClickSound

const val CRAFTING_GRID_SIZE = 9

/**
 * A 3×3 grid that restocks itself from a storage network after every craft.
 *
 * This is the cheap 80% of what autocrafting buys you: you still assemble the recipe by hand once, but
 * you never have to walk back to a chest for the next batch.
 *
 * Lives apart from any one block because two things have one: the crafting terminal, whose grid is
 * persisted in the block, and the wireless terminal, whose grid lasts as long as the window is open. All
 * this needs is somewhere to keep nine stacks and a network to pull from, so that is all it asks for.
 */
internal class CraftingGrid(
    private val inventory: Inventory,
    private val world: () -> World,
    private val network: () -> StorageNetwork?,
    private val maxBulkCrafts: () -> Int,
    private val onCrafted: () -> Unit
) {

    /**
     * Shared between all viewers of one grid, so every open window shows the same result.
     */
    val result: MutableProvider<ItemStack?> = mutableProvider<ItemStack?>(null)

    /**
     * Set while [craft] is running.
     *
     * Every write to the grid fires the post-update handler, and a bulk craft writes to up to nine slots
     * on each of up to `max_bulk_crafts` passes — so without this a single shift-click asked Bukkit to
     * match a recipe several hundred times over, to display a result nobody sees until the click is
     * over. [craft] recomputes once when it is done.
     */
    private var craftInProgress = false

    init {
        inventory.addPostUpdateHandler { updateResult() }
        updateResult()
    }

    fun updateResult() {
        if (!craftInProgress)
            result.set(computeResult())
    }

    /**
     * The crafting matrix as Bukkit wants it: never null, empty stacks for empty slots.
     */
    private fun matrix(): Array<ItemStack> =
        Array(CRAFTING_GRID_SIZE) { inventory.getItem(it) ?: ItemStack.empty() }

    private fun computeResult(): ItemStack? {
        val matrix = matrix()
        if (matrix.all { it.isEmpty })
            return null

        return Bukkit.getCraftingRecipe(matrix, world())?.result?.takeUnless { it.isEmpty }
    }

    /**
     * Crafts once, or up to `max_bulk_crafts` times on a shift-click, topping the grid back up from the
     * network after every craft.
     */
    fun craft(player: Player, bulk: Boolean) {
        val limit = if (bulk) maxBulkCrafts() else 1
        var crafts = 0

        craftInProgress = true
        try {
            while (crafts < limit) {
                val matrix = matrix()
                val output = Bukkit.getCraftingRecipe(matrix, world())?.result?.takeUnless { it.isEmpty }
                    ?: break

                // remember the layout before consuming so the grid can be refilled with the same types
                val layout = Array(CRAFTING_GRID_SIZE) { ItemType.of(matrix[it]) }
                // the output is earned by the consumption, so it is not handed over until the grid says
                // the whole recipe was actually paid for
                if (!consumeGrid(player, matrix))
                    break

                giveToPlayer(player, output.clone())
                refillGrid(player, layout)

                crafts++
            }
        } finally {
            craftInProgress = false
        }

        if (crafts > 0) {
            updateResult()
            onCrafted()
        }
    }

    /**
     * Takes one of every ingredient out of the grid, and answers whether the whole recipe was paid for.
     */
    private fun consumeGrid(player: Player, matrix: Array<ItemStack>): Boolean {
        val paid = ArrayList<ItemStack>(CRAFTING_GRID_SIZE)
        val remainders = ArrayList<ItemStack>(CRAFTING_GRID_SIZE)

        for (slot in 0..<CRAFTING_GRID_SIZE) {
            val stack = matrix[slot]
            if (stack.isEmpty)
                continue

            val remainder = stack.type.craftingRemainingItem

            val left = when {
                stack.amount > 1 -> stack.clone().apply { amount -= 1 }
                remainder != null -> ItemStack.of(remainder)
                else -> null
            }

            // A refused write leaves that ingredient sitting in the grid, so the recipe has only been
            // half paid for — and collecting a whole output for half a recipe is how a crafting grid
            // mints items. Put back what was already taken and let the craft simply not happen.
            if (!inventory.setItem(SELF, slot, left)) {
                paid.forEach { giveToPlayer(player, it) }
                return false
            }

            paid += stack.clone().apply { amount = 1 }

            // the slot could only keep one of the two, so the remainder has to go to the player — but
            // only once the whole grid is paid, or a later refusal would refund an ingredient and hand
            // over its remainder as well
            if (stack.amount > 1 && remainder != null)
                remainders += ItemStack.of(remainder)
        }

        remainders.forEach { giveToPlayer(player, it) }
        return true
    }

    private fun refillGrid(player: Player, layout: Array<ItemType?>) {
        val network = network() ?: return

        for (slot in 0..<CRAFTING_GRID_SIZE) {
            if (inventory.getItem(slot) != null)
                continue

            val type = layout[slot] ?: continue
            val taken = network.extract(type, 1L).toInt()
            if (taken <= 0)
                continue

            // the slot can refuse the write, and an item that has left the network without arriving
            // anywhere is simply gone
            if (inventory.setItem(SELF, slot, type.createStack(taken)))
                continue

            // it came out of the network a moment ago so it normally goes straight back; if the network
            // filled up in between, the player gets it rather than nobody
            val leftover = network.insert(type, taken.toLong()).toInt()
            if (leftover > 0)
                giveToPlayer(player, type.createStack(leftover))
        }
    }

    private fun giveToPlayer(player: Player, stack: ItemStack) {
        val leftover = player.inventory.addItemCorrectly(stack)
        if (leftover > 0)
            player.world.dropItemNaturally(player.location, stack.clone().apply { amount = leftover })
    }

    /**
     * Empties the grid into the network, then into the player, then onto the floor.
     *
     * For a grid that outlives its window this is never called; for one that does not, it is the whole
     * difference between "put the parts back" and "the parts are gone".
     */
    fun returnContents(player: Player) {
        for (slot in 0..<CRAFTING_GRID_SIZE) {
            val stack = inventory.getItem(slot) ?: continue
            if (!inventory.setItem(SELF, slot, null))
                continue

            val type = ItemType.of(stack)
            val leftover = if (type == null) stack.amount else network()?.insert(type, stack.amount.toLong())?.toInt() ?: stack.amount
            if (leftover <= 0)
                continue

            val rest = stack.clone().apply { amount = leftover }
            val overflow = player.inventory.addItemCorrectly(rest)
            if (overflow > 0)
                player.world.dropItemNaturally(player.location, stack.clone().apply { amount = overflow })
        }

        updateResult()
    }

    /**
     * The result slot: what the grid currently makes, and the button that makes it.
     */
    fun resultItem(): Item = item {
        itemProvider by result.map { stack ->
            if (stack == null) {
                ItemProvider.EMPTY
            } else {
                ItemBuilder(stack).addLoreLines(
                    Component.translatable("menu.smartstorage.crafting_terminal.hint", NamedTextColor.DARK_GRAY)
                        .withoutPreFormatting()
                )
            }
        }
        onClick {
            if (result.get() == null)
                return@onClick

            player.playClickSound()
            craft(player, clickType.isShiftClick)
        }
    }

    companion object {

        /**
         * Marks the writes this class makes to the grid. The post-update handler does not look at it —
         * [craftInProgress] is what stops a bulk craft recomputing per slot — but the reason still has to
         * exist, and a shared one is one fewer thing for a caller to supply.
         */
        private val SELF = object : UpdateReason {}

    }

}
