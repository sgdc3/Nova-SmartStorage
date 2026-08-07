package it.sgdc3.smartstorage.item

import it.sgdc3.smartstorage.SmartStorage
import it.sgdc3.smartstorage.storage.CellData
import it.sgdc3.smartstorage.storage.CellSummary
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.item.ItemUtils
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.item.retrieveData
import xyz.xenondevs.nova.util.item.storeData
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.item.behavior.ItemBehaviorFactory

/**
 * Turns an item into a storage cell and carries its tier limits.
 *
 * The contents travel with the item — pull a cell out of a drive bay, walk it across the map, drop it
 * into another bay and everything is still there.
 */
class StorageCellBehavior(item: NovaItem) : ItemBehavior {

    val maxTypes: Int by item.config.entry<Int>("max_types")
    val maxItems: Long by item.config.entry<Long>("max_items")

    /**
     * Reads the contents [itemStack] carries, using this behavior's tier limits.
     */
    fun read(itemStack: ItemStack): CellData =
        CellData.fromCompound(itemStack.retrieveData<Compound>(SmartStorage, DATA_KEY), maxTypes, maxItems)

    /**
     * Writes [data] onto [itemStack], in place.
     *
     * Mutating rather than copying lets a drive bay update the stack sitting in its inventory without
     * going through `setItem`, which would mean firing inventory events from inside an inventory event.
     */
    fun write(itemStack: ItemStack, data: CellData) {
        itemStack.storeData(SmartStorage, DATA_KEY, data.toCompound())
        // the tooltip reads this instead of the contents; see CellSummary for why
        itemStack.storeData(SmartStorage, SUMMARY_KEY, data.toSummary(PREVIEW_ENTRIES))
    }

    override fun modifyClientSideStack(player: Player?, server: ItemStack, client: ItemStack): ItemStack {
        // this runs for every clientbound packet carrying the item, per viewer, so it reads the digest
        // rather than the cell — falling back to the cell only for one written before digests existed
        val data = CellSummary.fromCompound(server.retrieveData<Compound>(SmartStorage, SUMMARY_KEY))
            ?: read(server).let { CellSummary(it.total, it.usedTypes, it.preview(PREVIEW_ENTRIES)) }

        val lore = client.lore() ?: mutableListOf()
        lore += Component.translatable(
            "item.smartstorage.storage_cell.lore.items",
            NamedTextColor.GRAY,
            Component.text(data.total, NamedTextColor.GREEN),
            Component.text(maxItems, NamedTextColor.GREEN)
        ).withoutPreFormatting()
        lore += Component.translatable(
            "item.smartstorage.storage_cell.lore.types",
            NamedTextColor.GRAY,
            Component.text(data.usedTypes, NamedTextColor.GREEN),
            Component.text(maxTypes, NamedTextColor.GREEN)
        ).withoutPreFormatting()

        for ((type, amount) in data.preview) {
            lore += Component.text()
                .color(NamedTextColor.DARK_GRAY)
                .append(Component.text("$amount× "))
                .append(ItemUtils.getName(type.stack))
                .build()
                .withoutPreFormatting()
        }

        val remaining = data.usedTypes - PREVIEW_ENTRIES
        if (remaining > 0) {
            lore += Component.translatable(
                "item.smartstorage.storage_cell.lore.more",
                NamedTextColor.DARK_GRAY,
                Component.text(remaining)
            ).withoutPreFormatting()
        }

        client.lore(lore)
        return client
    }

    companion object : ItemBehaviorFactory<StorageCellBehavior> {

        private const val DATA_KEY = "cell"
        private const val SUMMARY_KEY = "cell_summary"
        private const val PREVIEW_ENTRIES = 5

        override fun create(item: NovaItem) = StorageCellBehavior(item)

        /**
         * The cell behavior of [itemStack], or null if it isn't a storage cell.
         */
        fun of(itemStack: ItemStack?): StorageCellBehavior? =
            itemStack?.novaItem?.getBehaviorOrNull<StorageCellBehavior>()

    }

}
