package it.sgdc3.smartstorage.item

import it.sgdc3.smartstorage.SmartStorage
import it.sgdc3.smartstorage.storage.FluidCellData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.config.ConfigProvider
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.registry.RegistryEntry
import xyz.xenondevs.nova.util.NumberFormatUtils
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.item.retrieveData
import xyz.xenondevs.nova.util.item.storeData
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.item.behavior.ItemBehaviorFactory
import xyz.xenondevs.nova.world.item.getBehaviorOrNull
import xyz.xenondevs.nova.world.item.itemType

/**
 * Turns an item into a fluid cell and carries its capacity.
 *
 * The contents travel with the item, exactly as a storage cell's do.
 *
 * Unlike a storage cell this needs no digest written beside the contents: a fluid cell holds at most as
 * many entries as Nova has fluid types, which is two, so the tooltip can afford to decode the real
 * thing every time it is drawn.
 */
class FluidCellBehavior(config: ConfigProvider) : ItemBehavior {

    // the 16b cell's, for the same reason as the storage cell's: less than promised rather than more
    val maxAmount: Long by config.entry<Long>(16_000L, "max_amount")

    fun read(itemStack: ItemStack): FluidCellData =
        FluidCellData.fromCompound(itemStack.retrieveData<Compound>(SmartStorage, DATA_KEY), maxAmount)

    /**
     * Writes [data] onto [itemStack], in place — see [StorageCellBehavior.write] for why in place.
     */
    fun write(itemStack: ItemStack, data: FluidCellData) {
        itemStack.storeData(SmartStorage, DATA_KEY, data.toCompound())
    }

    override fun modifyClientSideStack(player: Player?, server: ItemStack, client: ItemStack): ItemStack {
        val data = read(server)

        val lore = client.lore() ?: mutableListOf()
        lore += Component.text(
            NumberFormatUtils.getFluidString(data.total, maxAmount),
            NamedTextColor.GRAY
        ).withoutPreFormatting()

        for ((type, amount) in data.entries()) {
            lore += Component.text()
                .color(NamedTextColor.DARK_GRAY)
                .append(Component.translatable(type.localizedName))
                .append(Component.text(": " + NumberFormatUtils.getFluidString(amount)))
                .build()
                .withoutPreFormatting()
        }

        client.lore(lore)
        return client
    }

    companion object : ItemBehaviorFactory<FluidCellBehavior> {

        private const val DATA_KEY = "fluid_cell"

        override fun create(entry: RegistryEntry.Paper<ItemType>, config: ConfigProvider) = FluidCellBehavior(config)

        /**
         * The fluid cell behavior of [itemStack], or null if it isn't a fluid cell.
         */
        fun of(itemStack: ItemStack?): FluidCellBehavior? =
            itemStack?.itemType?.getBehaviorOrNull<FluidCellBehavior>()

    }

}
