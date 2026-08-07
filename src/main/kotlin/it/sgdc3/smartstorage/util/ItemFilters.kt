package it.sgdc3.smartstorage.util

import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemFilter
import xyz.xenondevs.nova.world.item.behavior.ItemFilterContainer

/**
 * Whether this stack is a configured item filter of any kind.
 *
 * [ItemFilterContainer] lives in Nova's core rather than in Logistics, so filter items from any addon —
 * Logistics' included — work in our filter slots without this addon depending on any of them.
 */
fun ItemStack?.isItemFilter(): Boolean =
    this?.novaItem?.hasBehavior<ItemFilterContainer<*>>() == true

/**
 * Reads the [ItemFilter] configured in this stack, or null if it isn't a filter item.
 *
 * Star-projected on purpose: callers only ever ask a filter whether it [allows][ItemFilter.allows] a
 * stack, and pinning the type parameter down would force every call site through the same generic
 * gymnastics for no benefit.
 */
fun ItemStack?.getItemFilter(): ItemFilter<*>? {
    val container = this?.novaItem?.getBehaviorOrNull<ItemFilterContainer<*>>()
        ?: return null

    return container.getFilter(this)
}
