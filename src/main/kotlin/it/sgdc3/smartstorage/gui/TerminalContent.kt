package it.sgdc3.smartstorage.gui

import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.storage.SortMode
import it.sgdc3.smartstorage.storage.StorageEntry
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import xyz.xenondevs.commons.provider.MutableProvider
import xyz.xenondevs.commons.provider.Provider
import xyz.xenondevs.commons.provider.combinedProvider
import xyz.xenondevs.commons.provider.mutableProvider
import xyz.xenondevs.invui.dsl.IngredientsDsl
import xyz.xenondevs.invui.dsl.item
import xyz.xenondevs.invui.dsl.scrollItemsGui
import xyz.xenondevs.invui.gui.Markers
import xyz.xenondevs.invui.gui.ScrollGui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.nova.ui.menu.item.scrollDownItem
import xyz.xenondevs.nova.ui.menu.item.scrollUpItem
import xyz.xenondevs.nova.util.addItemCorrectly
import xyz.xenondevs.nova.util.component.adventure.toPlainText
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.item.ItemUtils
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.world.item.DefaultGuiItems
import kotlin.math.max
import kotlin.math.min

/**
 * The list of what a storage network holds, and everything a player can do to it by clicking.
 *
 * Lives apart from any one block because there are now two things that show it: the terminals, which are
 * tile entities on the network, and the wireless terminal, which is an item that is not on anything. All
 * it needs is a way to reach a network, so that is all it asks for.
 *
 * The list is exposed as a reactive [entries] provider, so every open window re-renders on its own when
 * the network changes; there is no per-window bookkeeping to keep in sync.
 */
internal class TerminalContent(private val network: () -> StorageNetwork?) {

    val entries: MutableProvider<List<StorageEntry>> = mutableProvider(emptyList())

    fun refresh() {
        val network = network()
        val next = if (network == null || !network.isOnline) {
            emptyList()
        } else {
            network.snapshot().map { (type, amount) -> StorageEntry(type, amount) }
        }

        // The provider invalidates on version rather than on value, so setting an identical list still
        // rebuilds every derived one — for a terminal that is the whole item list, several hundred
        // ItemBuilders and components, once a second, for a network nobody has touched. Comparing the
        // lists first is one pass over data we have already built.
        if (next != entries.get())
            entries.set(next)
    }

    //<editor-fold desc="terminal interactions", defaultstate="collapsed">

    /**
     * Moves [amount] items of [type] from the network onto the player's cursor.
     */
    private fun takeToCursor(player: Player, type: ItemType, amount: Int) {
        if (!player.itemOnCursor.isEmpty)
            return

        val network = network() ?: return
        val taken = network.extract(type, amount.toLong()).toInt()
        if (taken <= 0)
            return

        player.setItemOnCursor(type.createStack(taken))
        refresh()
    }

    /**
     * Moves [amount] items of [type] from the network into the player's inventory, returning whatever
     * did not fit.
     */
    private fun takeToInventory(player: Player, type: ItemType, amount: Int) {
        val network = network() ?: return
        val taken = network.extract(type, amount.toLong()).toInt()
        if (taken <= 0)
            return

        val leftover = player.inventory.addItemCorrectly(type.createStack(taken))
        if (leftover > 0) {
            // put back what did not fit, and drop the rest rather than deleting it
            val rejected = network.insert(type, leftover.toLong()).toInt()
            if (rejected > 0)
                player.world.dropItemNaturally(player.location, type.createStack(rejected))
        }

        refresh()
    }

    /**
     * Pushes the player's cursor stack into the network, keeping whatever did not fit.
     *
     * [all] follows the vanilla convention: left click stores the whole stack, right click one item.
     */
    private fun depositCursor(player: Player, all: Boolean) {
        val cursor = player.itemOnCursor
        val type = ItemType.of(cursor) ?: return
        val network = network() ?: return

        val offered = if (all) cursor.amount else 1
        val deposited = offered - network.insert(type, offered.toLong()).toInt()
        if (deposited <= 0)
            return

        val remaining = cursor.amount - deposited
        player.setItemOnCursor(if (remaining <= 0) null else type.createStack(remaining))
        refresh()
    }

    //</editor-fold>

    //<editor-fold desc="gui building blocks", defaultstate="collapsed">

    /**
     * Turns the raw network contents into the list of clickable items a terminal shows, applying the
     * player's current search text and sort order.
     *
     * The list is padded with invisible drop targets so that *every* visible slot of the grid accepts a
     * cursor stack, not just the ones that happen to hold an item. Without this, storing something is
     * only possible by aiming at an existing entry — and impossible on an empty network.
     *
     * [columns] and [visibleSlots] describe the grid this provider feeds; a terminal that shows the same
     * contents in two differently shaped guis needs one provider per gui.
     */
    fun contentProvider(
        player: Player,
        filter: Provider<String>,
        sortMode: Provider<SortMode>,
        columns: Int,
        visibleSlots: Int
    ): Provider<List<Item>> = combinedProvider(entries, filter, sortMode) { entries, filter, sortMode ->
        // Rendering an item's name and flattening it to plain text is by far the most expensive thing
        // here, so it is done at most once per entry. `compareBy` re-evaluates its selector on every
        // comparison, which turned sorting by name into O(n log n) component renders; and when the list
        // is sorted by amount with no search text, no name is needed at all.
        val needsName = sortMode == SortMode.NAME || filter.isNotBlank()
        val named = entries.map { entry ->
            entry to if (needsName) ItemUtils.getName(entry.type.stack).toPlainText(player) else ""
        }

        val items = named.asSequence()
            .filter { filter.isBlank() || it.second.contains(filter, ignoreCase = true) }
            .sortedWith(comparatorFor(sortMode))
            .mapTo(ArrayList()) { entryItem(it.first) }

        // fill up to a whole number of rows, and never leave the first screen half-dead
        val rows = (items.size + columns - 1) / columns
        val padded = max(visibleSlots, rows * columns)
        repeat(padded - items.size) { items += depositTargetItem() }

        items
    }

    /**
     * An invisible slot that swallows a cursor stack into the network. The gui texture already draws the
     * slot underneath, so there is nothing to render.
     */
    private fun depositTargetItem(): Item = item {
        itemProvider by ItemProvider.EMPTY
        onClick {
            if (!player.itemOnCursor.isEmpty)
                depositCursor(player, all = !clickType.isRightClick)
        }
    }

    /**
     * Orders entries that already carry their rendered name, so neither the filter nor the sort has to
     * render one.
     */
    private fun comparatorFor(sortMode: SortMode): Comparator<Pair<StorageEntry, String>> =
        when (sortMode) {
            SortMode.AMOUNT -> compareByDescending { it.first.amount }
            SortMode.NAME -> compareBy { it.second }
        }

    private fun entryItem(entry: StorageEntry): Item = item {
        itemProvider by ItemBuilder(entry.type.createStack(min(entry.amount, entry.type.maxStackSize.toLong()).toInt()))
            .addLoreLines(
                Component.translatable(
                    "menu.smartstorage.terminal.stored",
                    NamedTextColor.GRAY,
                    Component.text(entry.amount, NamedTextColor.GREEN)
                ).withoutPreFormatting(),
                Component.translatable("menu.smartstorage.terminal.hint", NamedTextColor.DARK_GRAY).withoutPreFormatting()
            )

        onClick {
            if (!player.itemOnCursor.isEmpty) {
                depositCursor(player, all = !clickType.isRightClick)
                return@onClick
            }

            val stackSize = entry.type.maxStackSize
            when {
                clickType.isShiftClick -> takeToInventory(player, entry.type, stackSize)
                clickType == ClickType.LEFT -> takeToCursor(player, entry.type, stackSize)
                clickType == ClickType.RIGHT -> takeToCursor(player, entry.type, max(1, stackSize / 2))
                else -> return@onClick
            }
        }
    }

    fun sortButton(sortMode: MutableProvider<SortMode>): Item = item {
        itemProvider by sortMode.map { mode ->
            ItemBuilder(Material.HOPPER).setName(
                Component.translatable(mode.localizationKey, NamedTextColor.GRAY).withoutPreFormatting()
            )
        }
        onClick {
            if (clickType.isLeftClick) {
                sortMode.set(sortMode.get().next())
                player.playClickSound()
            }
        }
    }

    fun clearFilterButton(filter: MutableProvider<String>): Item = item {
        itemProvider by filter.map { text ->
            ItemBuilder(Material.NAME_TAG).setName(
                Component.translatable(
                    "menu.smartstorage.terminal.filter",
                    NamedTextColor.GRAY,
                    Component.text(text.ifBlank { "-" }, NamedTextColor.GREEN)
                ).withoutPreFormatting()
            )
        }
        onClick {
            if (clickType.isLeftClick && filter.get().isNotBlank()) {
                filter.set("")
                player.playClickSound()
            }
        }
    }

    /**
     * The scrolling list of network contents, shared by every terminal screen.
     */
    fun contentGui(
        items: Provider<List<Item>>,
        vararg structure: String,
        extraIngredients: IngredientsDsl.() -> Unit = {}
    ): ScrollGui<Item> = scrollItemsGui(*structure) {
        // HORIZONTAL, and the name is a trap: in InvUI the orientation says what a *line* is, not which
        // way the gui scrolls. VERTICAL makes a line a column, so the list fills top-to-bottom then
        // rightwards and scrolls sideways — under two buttons that say up and down. This also makes the
        // padding below mean what it says, since a line is now genuinely a row of `columns` entries.
        'x' by Markers.CONTENT_LIST_SLOT_HORIZONTAL
        'u' by scrollUpItem(line)
        'd' by scrollDownItem(line, maxLine)
        extraIngredients()
        content by items
    }

    //</editor-fold>

    companion object {

        /**
         * The icon drawn in the empty deposit slots.
         *
         * Without it that row is pixel-identical to the (empty) list slots above it, so nothing tells the
         * player it is an input rather than more of the same.
         */
        fun depositBackground(): ItemProvider =
            DefaultGuiItems.TP_ARROW_DOWN_ON.createClientsideItemBuilder()
                .setName(
                    Component.translatable("menu.smartstorage.terminal.deposit", NamedTextColor.GRAY)
                        .withoutPreFormatting()
                )
                .addLoreLines(
                    Component.translatable("menu.smartstorage.terminal.deposit.hint", NamedTextColor.DARK_GRAY)
                        .withoutPreFormatting()
                )

        fun searchState(): MutableProvider<String> = mutableProvider("")

        fun sortState(): MutableProvider<SortMode> = mutableProvider(SortMode.AMOUNT)

    }

}
