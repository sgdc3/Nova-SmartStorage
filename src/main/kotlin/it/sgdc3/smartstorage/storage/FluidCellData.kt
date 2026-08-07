package it.sgdc3.smartstorage.storage

import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import java.util.EnumMap
import kotlin.math.min

/**
 * The contents of a single fluid cell.
 *
 * Bounded on one axis rather than two, which is the whole difference from [CellData]: Nova has exactly
 * two fluid types, so a limit on how many of them a cell may hold would be a knob with no interesting
 * setting. What a fluid cell is worth is [maxAmount], shared across whatever is in it — put four
 * buckets of lava in a cell and there is that much less room for water.
 *
 * Amounts are Nova's fluid units throughout: 1000 to the bucket.
 *
 * Instances are mutated only while [StorageLock] is held.
 */
class FluidCellData(
    val maxAmount: Long,
    private val contents: EnumMap<FluidType, Long> = EnumMap(FluidType::class.java)
) {

    var total: Long = contents.values.sum()
        private set

    val isEmpty: Boolean
        get() = contents.isEmpty()

    fun amountOf(type: FluidType): Long =
        contents[type] ?: 0L

    fun collectInto(index: MutableMap<FluidType, Long>) {
        for ((type, amount) in contents)
            index.merge(type, amount) { a, b -> a + b }
    }

    /**
     * Stores up to [amount] of [type] and returns how much was actually stored.
     */
    fun insert(type: FluidType, amount: Long): Long {
        if (amount <= 0L)
            return 0L

        val inserted = min(amount, maxAmount - total)
        if (inserted <= 0L)
            return 0L

        contents[type] = (contents[type] ?: 0L) + inserted
        total += inserted
        return inserted
    }

    /**
     * Removes up to [amount] of [type] and returns how much was actually removed.
     */
    fun extract(type: FluidType, amount: Long): Long {
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
     * The contents in a stable order, for the cell item's lore.
     */
    fun entries(): List<Pair<FluidType, Long>> =
        contents.entries.map { it.key to it.value }

    fun toCompound(): Compound {
        val types = ArrayList<String>(contents.size)
        val amounts = ArrayList<Long>(contents.size)
        for ((type, amount) in contents) {
            types += type.name
            amounts += amount
        }

        val compound = Compound()
        compound["types"] = types
        compound["amounts"] = amounts
        return compound
    }

    companion object {

        /**
         * The fluid types written by [toCompound], indexed by the name it wrote.
         *
         * Names rather than ordinals because an ordinal is a promise about declaration order that Nova
         * has not made. Entries naming a type this build does not know are dropped on read, which is the
         * same policy [CellData] has for an item whose plugin has been removed.
         */
        private val BY_NAME = FluidType.entries.associateBy(FluidType::name)

        fun fromCompound(compound: Compound?, maxAmount: Long): FluidCellData {
            val contents = EnumMap<FluidType, Long>(FluidType::class.java)

            if (compound != null) {
                val types: ArrayList<String>? = compound["types"]
                val amounts: ArrayList<Long>? = compound["amounts"]

                if (types != null && amounts != null) {
                    for (i in 0..<min(types.size, amounts.size)) {
                        val type = BY_NAME[types[i]] ?: continue
                        val amount = amounts[i]
                        if (amount > 0L)
                            contents.merge(type, amount) { a, b -> a + b }
                    }
                }
            }

            return FluidCellData(maxAmount, contents)
        }

    }

}
