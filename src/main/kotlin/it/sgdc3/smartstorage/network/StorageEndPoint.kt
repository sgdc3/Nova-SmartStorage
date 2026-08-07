package it.sgdc3.smartstorage.network

import it.sgdc3.smartstorage.storage.ItemType
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType

/**
 * The values [StorageProvider.priority] may take, and where a fresh device starts.
 *
 * The same 0–100 scale Logistics gives its pipes, and the same midpoint, so that a player who has met
 * one already knows what the other means — and so there is as much room to demote something as there is
 * to promote it.
 */
val PRIORITY_RANGE = 0..100
const val DEFAULT_PRIORITY = 50

/**
 * A tile-entity that takes part in a storage network.
 *
 * [storageNetwork] is pushed in by [StorageNetwork] when it is built and cleared by the implementation
 * when it is broken or unloaded. Nova rebuilds [xyz.xenondevs.nova.world.block.tileentity.network.Network]
 * objects whenever the topology changes, so end points must never assume the reference stays alive.
 */
interface StorageEndPoint : NetworkEndPoint {

    val storageHolder: StorageHolder

    /**
     * The storage network this end point currently belongs to, or null if it isn't part of one.
     */
    var storageNetwork: StorageNetwork?

    /**
     * The storage this end point contributes, which is usually itself and sometimes several things.
     *
     * A storage connector returns one per container it is mounted on, so each of them carries its own
     * filter and its own priority: a chest and a barrel on opposite sides of the same connector are two
     * independent pieces of storage, not one.
     *
     * Re-read on every network tick, so a provider may come and go without the topology changing.
     */
    val storageProviders: List<StorageProvider>
        get() = if (this is StorageProvider) listOf(this) else emptyList()

    /**
     * The fluid storage this end point contributes, on exactly the same terms as [storageProviders].
     *
     * Separate lists rather than one, because the two kinds of storage share nothing but their
     * bookkeeping: a drive bay is both at once and a chest is only ever the first.
     */
    val fluidProviders: List<FluidProvider>
        get() = if (this is FluidProvider) listOf(this) else emptyList()

}

/**
 * An end point that offers this system's *fluids* to a foreign fluid network as if it were a tank.
 *
 * Counted rather than merely listed, and that is the whole reason it exists. Nova's fluid distributor
 * sizes a transfer from `providers.sumOf { it.amount }` and only afterwards asks the providers to
 * produce it — so two gateways over one system, each honestly reporting the same aggregate, promise
 * twice what exists. See [it.sgdc3.smartstorage.network.NetworkFluidView.amount] for how the promise is
 * split between them.
 */
interface FluidGateway : StorageEndPoint

/**
 * A device a wireless terminal can reach the network through.
 *
 * Purely a marker, and deliberately so: an access point has no reach of its own. Reach belongs to the
 * *terminal*, which is the thing that gets carried away and the thing that takes upgrades — so a point
 * is only ever a place a network can be found from, and all a terminal needs of it is where it is.
 *
 * It exists so [StorageNetworkGroup] can hand back the points on a given system without the network
 * package having to know what one is.
 */
interface WirelessNode : StorageEndPoint

/**
 * What a piece of storage is worth to the network regardless of what it stores.
 *
 * Exists so a device that holds both items and fluids — a drive bay, or one side of a connector — has
 * one priority and one identity rather than two that could disagree.
 */
interface NetworkProvider {

    /**
     * Insertion prefers higher priorities, extraction prefers lower ones, so a high priority provider
     * fills up first and empties last.
     *
     * That single rule is what lets a player say "keep the iron in the drive bays and let the chests
     * take the overflow": raise the bays and they are filled before the chests and drained after them,
     * so the chests hold whatever is spilling over at any moment.
     *
     * Kept within [PRIORITY_RANGE]. It is only ever an ordering, so the range costs nothing — and it has
     * to stay non-negative because the menus show it with Nova's numbered GUI item, whose models start
     * at zero.
     */
    val priority: Int

    /**
     * What this provider is ultimately backed by, so that two providers over the *same* storage can be
     * recognised and only one of them counted.
     *
     * This is not hypothetical. Bukkit hands both halves of a double chest the same
     * [org.bukkit.inventory.Inventory], so a connector on each half produces two providers over one
     * chest — as does a single chest with a connector on two of its sides. Counting it twice is not
     * merely a display error: [StorageNetworkGroup.countOf] feeds
     * [it.sgdc3.smartstorage.network.NetworkView.canTake], and Nova's item distributor adds to the
     * destination *before* calling `take`, which returns nothing. Promise sixty-four, deliver
     * thirty-two, and the difference has been created out of nothing.
     *
     * Defaults to the provider itself, which is right for anything that owns its storage outright.
     */
    val storageIdentity: Any
        get() = this

}

/**
 * Something that contributes storage capacity to the network — a drive bay, or one container of a
 * storage connector. Not a network node in its own right: it is reached through the
 * [StorageEndPoint.storageProviders] of one.
 *
 * Every function is called with [it.sgdc3.smartstorage.storage.StorageLock] already held.
 */
interface StorageProvider : NetworkProvider {

    /**
     * The number of storage cells currently installed, used for the network's energy draw.
     *
     * Counted here and not on [FluidProvider] so that a drive bay holding both kinds of disk reports
     * them once rather than twice.
     */
    val cellCount: Int

    val usedTypes: Int
    val totalTypes: Int
    val usedCount: Long
    val totalCount: Long

    /**
     * Whether one more item would fit.
     *
     * Separate from comparing [usedCount] against [totalCount] because some storage does not report a
     * capacity at all — a Logistics storage unit will say whether it is full but not how much it holds
     * in total — and treating "capacity unknown" as "no room" would quietly stop the network accepting
     * items.
     */
    val hasRoom: Boolean
        get() = usedCount < totalCount

    /**
     * Adds this provider's contents to [index], summing counts for types that are already present.
     */
    fun collectInto(index: MutableMap<ItemType, Long>)

    fun countOf(type: ItemType): Long

    /**
     * How much of [type] this provider would actually hand over if asked — which is not always what it
     * [holds][countOf].
     *
     * The two questions are genuinely different and confusing them mints items. A storage connector's
     * port with extraction switched off still *contains* a chest full of cobblestone, and a terminal is
     * right to show it; but Nova's item distributor gives the items to the destination first and only
     * then calls `take`, which cannot report a shortfall. So anything that answers "may I promise this"
     * has to ask here, and only a readout may ask [countOf].
     *
     * Defaults to [countOf], which is right for storage that has no way of refusing.
     */
    fun extractableCountOf(type: ItemType): Long = countOf(type)

    /**
     * The same distinction for [collectInto]: what this provider would hand over, not what it holds.
     */
    fun collectExtractableInto(index: MutableMap<ItemType, Long>) = collectInto(index)

    /**
     * Whether this provider already holds any [type], which is all insertion needs to know when it is
     * deciding where a stack should go.
     *
     * Separate from [countOf] because it can stop at the first match. For a container that is the
     * difference between scanning every slot and scanning until the item turns up — and insertion asks
     * this of every provider on the network before it places anything.
     */
    fun holds(type: ItemType): Boolean = countOf(type) > 0L

    /**
     * Stores up to [amount] items of [type] and returns how many were actually stored.
     */
    fun insert(type: ItemType, amount: Long): Long

    /**
     * Removes up to [amount] items of [type] and returns how many were actually removed.
     */
    fun extract(type: ItemType, amount: Long): Long

}

/**
 * Something that contributes *fluid* capacity to the network — a drive bay with fluid cells in it, or a
 * tank a storage connector is mounted on.
 *
 * Every member is named differently from its [StorageProvider] counterpart on purpose. A drive bay
 * implements both, and after erasure `collectInto(Map<ItemType, Long>)` and `collectInto(Map<FluidType,
 * Long>)` are the same method — so the names have to differ, and if two of them have to, all of them
 * should, or the pairs that happen to be distinguishable read as a different kind of thing from the
 * pairs that do not.
 *
 * Amounts are Nova's fluid units: 1000 to the bucket.
 *
 * Every function is called with [it.sgdc3.smartstorage.storage.StorageLock] already held.
 */
interface FluidProvider : NetworkProvider {

    val usedAmount: Long
    val totalAmount: Long

    /**
     * Whether one more unit would fit. Separate from comparing the two totals for the same reason
     * [StorageProvider.hasRoom] is.
     */
    val hasFluidRoom: Boolean
        get() = usedAmount < totalAmount

    fun collectFluidsInto(index: MutableMap<FluidType, Long>)

    fun amountOf(type: FluidType): Long

    /**
     * How much of [type] this provider would actually hand over — see [StorageProvider.extractableCountOf]
     * for why that is a different question from [amountOf], and what it costs to confuse them.
     */
    fun extractableAmountOf(type: FluidType): Long = amountOf(type)

    /**
     * Whether this provider already holds any [type], which is all insertion needs to know when it is
     * deciding where a bucket should go.
     */
    fun holdsFluid(type: FluidType): Boolean = amountOf(type) > 0L

    /**
     * Stores up to [amount] of [type] and returns how much was actually stored.
     */
    fun insertFluid(type: FluidType, amount: Long): Long

    /**
     * Removes up to [amount] of [type] and returns how much was actually removed.
     */
    fun extractFluid(type: FluidType, amount: Long): Long

}

/**
 * A [StorageEndPoint] that powers and bounds a network. Exactly one is required per network.
 */
interface StorageControllerNode : StorageEndPoint {

    /**
     * The maximum number of end points a network driven by this controller may contain.
     */
    val maxDevices: Int

    /**
     * Draws the energy needed to keep [devices] devices and [cells] cells running for one network tick.
     *
     * @return whether enough energy was available; when false the network goes offline for this tick.
     */
    fun tryConsume(devices: Int, cells: Int): Boolean

    /**
     * Hands the freshly computed network status to this controller so its menu can display it.
     */
    fun updateStatus(status: StorageNetworkStatus)

}
