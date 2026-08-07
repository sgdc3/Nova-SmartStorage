package it.sgdc3.smartstorage.storage

import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import kotlin.math.min

/**
 * The contents of a single storage cell.
 *
 * A cell is bounded on two axes, mirroring the mods this addon takes after: [maxTypes] distinct item
 * types and [maxItems] items in total. Running out of either is what pushes players to build bigger
 * cells rather than hoarding one.
 *
 * Instances are mutated only while [StorageLock] is held.
 */
class CellData(
    val maxTypes: Int,
    val maxItems: Long,
    private val contents: LinkedHashMap<ItemType, Long> = LinkedHashMap()
) {

    var total: Long = contents.values.sum()
        private set

    val usedTypes: Int
        get() = contents.size

    val isEmpty: Boolean
        get() = contents.isEmpty()

    fun countOf(type: ItemType): Long =
        contents[type] ?: 0L

    fun collectInto(index: MutableMap<ItemType, Long>) {
        for ((type, amount) in contents)
            index.merge(type, amount) { a, b -> a + b }
    }

    /**
     * Stores up to [amount] items of [type] and returns how many were actually stored.
     */
    fun insert(type: ItemType, amount: Long): Long {
        if (amount <= 0L)
            return 0L

        val existing = contents[type]
        if (existing == null && contents.size >= maxTypes)
            return 0L

        val inserted = min(amount, maxItems - total)
        if (inserted <= 0L)
            return 0L

        contents[type] = (existing ?: 0L) + inserted
        total += inserted
        return inserted
    }

    /**
     * Removes up to [amount] items of [type] and returns how many were actually removed.
     */
    fun extract(type: ItemType, amount: Long): Long {
        if (amount <= 0L)
            return 0L

        val existing = contents[type] ?: return 0L
        val extracted = min(amount, existing)

        if (extracted == existing) {
            contents.remove(type)
        } else {
            contents[type] = existing - extracted
        }

        total -= extracted
        return extracted
    }

    /**
     * The first [limit] entries, used to build the cell item's lore.
     */
    fun preview(limit: Int): List<Pair<ItemType, Long>> =
        contents.entries.asSequence()
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
            .toList()

    /**
     * A digest of this cell for its item tooltip: the two totals and the handful of entries the lore
     * actually prints.
     *
     * Written beside the full contents so the tooltip never has to decode them. CBF deserializes a
     * compound entry on first access rather than up front, so reading only this key is genuinely
     * cheaper — and it is read *often*: the tooltip is rebuilt for every clientbound packet carrying
     * the item, for every viewer. A drive bay of twelve 64k cells is otherwise several hundred item
     * deserializations and twelve sorts per window repaint, to print five lines each.
     */
    fun toSummary(previewLimit: Int): Compound {
        val preview = preview(previewLimit)

        val compound = Compound()
        compound["total"] = total
        compound["types"] = usedTypes
        compound["previewTypes"] = preview.mapTo(ArrayList<ItemStack>(preview.size)) { it.first.stack }
        compound["previewAmounts"] = preview.mapTo(ArrayList<Long>(preview.size)) { it.second }
        return compound
    }

    fun toCompound(): Compound {
        val types = ArrayList<ItemStack>(contents.size)
        val amounts = ArrayList<Long>(contents.size)
        for ((type, amount) in contents) {
            types += type.stack
            amounts += amount
        }

        val compound = Compound()
        compound["types"] = types
        compound["amounts"] = amounts
        return compound
    }

    companion object {

        /**
         * Rebuilds a [CellData] from [compound]. Entries whose item type no longer exists are dropped,
         * which is the sane behaviour when a plugin providing custom items is removed.
         */
        fun fromCompound(compound: Compound?, maxTypes: Int, maxItems: Long): CellData {
            val contents = LinkedHashMap<ItemType, Long>()

            if (compound != null) {
                val types: ArrayList<ItemStack>? = compound["types"]
                val amounts: ArrayList<Long>? = compound["amounts"]

                if (types != null && amounts != null) {
                    for (i in 0..<min(types.size, amounts.size)) {
                        val type = ItemType.of(types[i]) ?: continue
                        val amount = amounts[i]
                        if (amount > 0L)
                            contents.merge(type, amount) { a, b -> a + b }
                    }
                }
            }

            return CellData(maxTypes, maxItems, contents)
        }

    }

}
