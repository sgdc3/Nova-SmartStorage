package it.sgdc3.smartstorage.network

import it.sgdc3.smartstorage.storage.ItemType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import kotlin.math.min

/**
 * A [StorageProvider] with a name, a capacity and a memory of what it was asked.
 *
 * Real providers are a drive bay, a chest behind a connector or a wall of barrels, and none of them can
 * be built without most of Nova. What [Routing] needs of them is four questions and two commands, so a
 * fake that answers those honestly is the whole thing under test — plus the [log], which is how the
 * *order* it asked in becomes something a test can assert on rather than infer.
 */
internal class FakeProvider(
    private val name: String,
    override val priority: Int = DEFAULT_PRIORITY,
    private val capacity: Long = 1000L,
    override val storageIdentity: Any = Any(),
    /** Refuses everything, the way a side with its extraction switched off does. */
    private val readOnly: Boolean = false,
    private val writeOnly: Boolean = false,
    val log: MutableList<String> = ArrayList()
) : StorageProvider {

    private val contents = HashMap<ItemType, Long>()

    override val cellCount: Int get() = 1
    override val usedTypes: Int get() = contents.size
    override val totalTypes: Int get() = Int.MAX_VALUE
    override val usedCount: Long get() = contents.values.sum()
    override val totalCount: Long get() = capacity

    override fun collectInto(index: MutableMap<ItemType, Long>) {
        for ((type, amount) in contents)
            index.merge(type, amount, Long::plus)
    }

    override fun countOf(type: ItemType): Long = contents[type] ?: 0L

    override fun holds(type: ItemType): Boolean = countOf(type) > 0L

    override fun insert(type: ItemType, amount: Long): Long {
        log += "$name.insert($amount)"
        if (readOnly)
            return 0L

        val stored = min(amount, capacity - usedCount)
        if (stored <= 0L)
            return 0L

        contents.merge(type, stored, Long::plus)
        return stored
    }

    override fun extract(type: ItemType, amount: Long): Long {
        log += "$name.extract($amount)"
        if (writeOnly)
            return 0L

        val taken = min(amount, countOf(type))
        if (taken <= 0L)
            return 0L

        if (taken == contents[type]) contents.remove(type) else contents[type] = contents[type]!! - taken
        return taken
    }

    /**
     * A side that will not give up what it holds still *holds* it — which is exactly the asymmetry that
     * turned a connector into an item generator, so the fake reproduces it rather than smoothing it over.
     */
    override fun extractableCountOf(type: ItemType): Long =
        if (writeOnly) 0L else countOf(type)

    override fun collectExtractableInto(index: MutableMap<ItemType, Long>) {
        if (!writeOnly)
            collectInto(index)
    }

    /** Preloads contents without going through [insert], so a test's setup does not pollute the [log]. */
    fun given(type: ItemType, amount: Long) = apply { contents[type] = amount }

    override fun toString(): String = name

}

/**
 * The same for fluids. Separate because the two interfaces share nothing but their bookkeeping — see
 * [FluidProvider] for why every member is named differently.
 */
internal class FakeFluidProvider(
    private val name: String,
    override val priority: Int = DEFAULT_PRIORITY,
    private val capacity: Long = 16_000L,
    override val storageIdentity: Any = Any(),
    private val writeOnly: Boolean = false,
    val log: MutableList<String> = ArrayList()
) : FluidProvider {

    private val contents = HashMap<FluidType, Long>()

    override val usedAmount: Long get() = contents.values.sum()
    override val totalAmount: Long get() = capacity

    override fun collectFluidsInto(index: MutableMap<FluidType, Long>) {
        for ((type, amount) in contents)
            index.merge(type, amount, Long::plus)
    }

    override fun amountOf(type: FluidType): Long = contents[type] ?: 0L

    override fun holdsFluid(type: FluidType): Boolean = amountOf(type) > 0L

    override fun insertFluid(type: FluidType, amount: Long): Long {
        log += "$name.insert($amount)"
        val stored = min(amount, capacity - usedAmount)
        if (stored <= 0L)
            return 0L

        contents.merge(type, stored, Long::plus)
        return stored
    }

    override fun extractFluid(type: FluidType, amount: Long): Long {
        log += "$name.extract($amount)"
        if (writeOnly)
            return 0L

        val taken = min(amount, amountOf(type))
        if (taken <= 0L)
            return 0L

        if (taken == contents[type]) contents.remove(type) else contents[type] = contents[type]!! - taken
        return taken
    }

    override fun extractableAmountOf(type: FluidType): Long =
        if (writeOnly) 0L else amountOf(type)

    fun given(type: FluidType, amount: Long) = apply { contents[type] = amount }

    override fun toString(): String = name

}
