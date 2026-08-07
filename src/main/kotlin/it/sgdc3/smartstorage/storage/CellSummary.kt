package it.sgdc3.smartstorage.storage

import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import kotlin.math.min

/**
 * What a storage cell's tooltip needs, and nothing else.
 *
 * Kept beside the cell's real contents rather than derived from them, because a tooltip is rebuilt for
 * every clientbound packet carrying the item and decoding a full 64k cell to print five lines is the
 * kind of cost that only becomes visible when a drive bay full of them is on screen. Written by
 * [it.sgdc3.smartstorage.item.StorageCellBehavior.write], read by nothing else.
 */
class CellSummary(
    val total: Long,
    val usedTypes: Int,
    val preview: List<Pair<ItemType, Long>>
) {

    companion object {

        /**
         * Reads a summary, or null if [compound] carries none — which is the case for any cell written
         * before summaries existed. Callers fall back to decoding the cell itself.
         */
        fun fromCompound(compound: Compound?): CellSummary? {
            if (compound == null)
                return null

            val total: Long = compound["total"] ?: return null
            val usedTypes: Int = compound["types"] ?: return null
            val types: ArrayList<ItemStack>? = compound["previewTypes"]
            val amounts: ArrayList<Long>? = compound["previewAmounts"]

            val preview = ArrayList<Pair<ItemType, Long>>()
            if (types != null && amounts != null) {
                for (i in 0..<min(types.size, amounts.size)) {
                    // an item type can stop existing when the plugin providing it is removed; dropping
                    // the entry costs a tooltip line, which is the right price
                    val type = ItemType.of(types[i]) ?: continue
                    preview += type to amounts[i]
                }
            }

            return CellSummary(total, usedTypes, preview)
        }

    }

}
