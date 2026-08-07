package it.sgdc3.smartstorage.network

import it.sgdc3.smartstorage.registry.NetworkTypes
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.storage.StorageLock
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkGroup
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkGroupData
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import java.util.EnumMap
import kotlin.concurrent.withLock
import kotlin.math.max

private val TICK_DELAY by NetworkTypes.TICK_DELAY_PROVIDER

/**
 * Slack on top of the configured tick delay, so a lagging server never reads as orphaned.
 */
private const val MIN_STALE_TICKS = 40

/**
 * One storage system, and the thing this addon actually treats as "the network".
 *
 * Nova hands us a group per cluster: every [StorageNetwork] reachable from another through a shared node
 * lands in the same one. That distinction matters because only a cable *bridges* a network — an end
 * point terminates it. A device wedged between two cable runs therefore belongs to two networks at once,
 * and aggregating per network would leave each run seeing half the system. Every device this addon has
 * is an end point, so without this the obvious thing to build — a straight cable run with a connector or
 * an interface partway along it — would silently cut the system in two.
 *
 * So the aggregate lives here and [StorageNetwork] is a façade over it: one status, one ordered list of
 * providers and one energy draw per cluster, however many networks Nova split it into.
 *
 * The tick is intentionally light — status and energy. Items are not moved here: they travel through
 * Nova's item network via [it.sgdc3.smartstorage.tileentity.StorageInterface], or through a terminal.
 */
internal class StorageNetworkGroup(
    data: NetworkGroupData<StorageNetwork>
) : NetworkGroup<StorageNetwork>, NetworkGroupData<StorageNetwork> by data {

    private val endPoints: List<StorageEndPoint>
    private val controllers: List<StorageControllerNode>

    /**
     * Providers ordered by descending priority. Replaced wholesale on every tick so that priority
     * changes take effect without readers ever seeing a half-sorted list.
     */
    @Volatile
    private var providers: List<StorageProvider>

    /**
     * The same, for fluids. A separate list because the two kinds of storage overlap only partly: a
     * drive bay is in both, a chest only in the first, a tank only in the second.
     */
    @Volatile
    private var fluidProviders: List<FluidProvider>

    @Volatile
    var status: StorageNetworkStatus = StorageNetworkStatus.offline(OfflineReason.NO_CONTROLLER)
        private set

    /**
     * The server tick this group last ran on.
     *
     * Nova rebuilds a [StorageNetwork] for every end point that is still on *some* network, but a
     * topology change that leaves an end point with no storage connections at all simply drops it from
     * the node map: nothing rebuilds it and nothing clears the reference it is holding. That orphan
     * keeps pointing at this group, whose [status] is only ever written in [tick] and is therefore
     * frozen at whatever it last was — usually online. A terminal, or Nova's item network reaching
     * through a [it.sgdc3.smartstorage.tileentity.StorageInterface], would go on moving real items with
     * no controller, no device cap and no energy draw.
     *
     * Rather than trying to catch every path that can orphan a group, the group has to keep proving it
     * is still being ticked. Anything else is offline by definition.
     */
    @Volatile
    private var lastTick: Int = serverTick

    val isOnline: Boolean
        get() = status.isOnline && serverTick - lastTick <= max(MIN_STALE_TICKS, TICK_DELAY * 4)

    init {
        // a set, because a node shared by two of these networks — which is the whole reason they are
        // grouped — would otherwise be counted, powered and filled twice
        val endPoints = LinkedHashSet<StorageEndPoint>()
        for (network in networks)
            endPoints += network.endPoints

        this.endPoints = endPoints.toList()
        this.controllers = this.endPoints.filterIsInstance<StorageControllerNode>()
        this.providers = collectProviders()
        this.fluidProviders = collectFluidProviders()

        for (network in networks)
            network.group = this
    }

    /**
     * Rebuilt rather than sorted in place, because an end point may gain or lose providers without the
     * topology changing at all — a chest placed beside a connector is not a network event.
     *
     * The ordering and the deduplication are [Routing]'s, which is where the rules they follow are
     * stated and tested.
     */
    private fun collectProviders(): List<StorageProvider> =
        Routing.order(endPoints.asSequence().flatMap { it.storageProviders.asSequence() })

    /**
     * The same, on the same key: a device that stores both shares one identity, so a connector reaching
     * one tank from two sides is one provider in either list.
     */
    private fun collectFluidProviders(): List<FluidProvider> =
        Routing.order(endPoints.asSequence().flatMap { it.fluidProviders.asSequence() })

    override fun tick() {
        lastTick = serverTick

        val providers = collectProviders()
        this.providers = providers
        this.fluidProviders = collectFluidProviders()

        val devices = endPoints.size
        // fluid cells are counted here too: cellCount is declared on StorageProvider alone precisely so
        // that a drive bay holding both kinds of disk reports them once
        val cells = providers.sumOf(StorageProvider::cellCount)

        val status = computeStatus(providers, devices, cells)
        this.status = status
        for (controller in controllers)
            controller.updateStatus(status)
    }

    private fun computeStatus(
        providers: List<StorageProvider>,
        devices: Int,
        cells: Int
    ): StorageNetworkStatus {
        when {
            controllers.isEmpty() -> return StorageNetworkStatus.offline(OfflineReason.NO_CONTROLLER, devices, cells)
            controllers.size > 1 -> return StorageNetworkStatus.offline(OfflineReason.MULTIPLE_CONTROLLERS, devices, cells)
        }

        val controller = controllers[0]
        // -1 disables the cap, the convention Nova uses for its own max_complexity settings — without it
        // an admin copying that convention takes every storage network on the server offline for good
        val maxDevices = controller.maxDevices
        if (maxDevices >= 0 && devices > maxDevices)
            return StorageNetworkStatus.offline(OfflineReason.TOO_MANY_DEVICES, devices, cells)
        if (!controller.tryConsume(devices, cells))
            return StorageNetworkStatus.offline(OfflineReason.NO_ENERGY, devices, cells)

        return StorageNetworkStatus(null, devices, cells)
    }

    /**
     * How full the system is. Walks every provider, so it is computed on request rather than per tick —
     * for a connector port each of the four figures is a full pass over a container's contents, and the
     * only thing that reads them is one status icon.
     */
    fun totals(): StorageTotals {
        // the same precondition every other storage operation here has. Without it this is the one way
        // to read a dead group's providers, and it reports the contents of devices that may already have
        // been broken and dropped on the floor.
        if (!isOnline)
            return StorageTotals.EMPTY

        var usedTypes = 0
        var totalTypes = 0
        var usedCount = 0L
        var totalCount = 0L
        var usedFluid = 0L
        var totalFluid = 0L

        StorageLock.withLock {
            for (provider in providers) {
                usedTypes += provider.usedTypes
                totalTypes += provider.totalTypes
                usedCount += provider.usedCount
                totalCount += provider.totalCount
            }
            for (provider in fluidProviders) {
                usedFluid += provider.usedAmount
                totalFluid += provider.totalAmount
            }
        }

        return StorageTotals(usedTypes, totalTypes, usedCount, totalCount, usedFluid, totalFluid)
    }

    //<editor-fold desc="storage operations", defaultstate="collapsed">

    /**
     * Stores up to [amount] items of [type] and returns how many were left over.
     *
     * Higher priority providers are filled first, and within the same pass providers that already hold
     * [type] win over empty ones so that a stack doesn't get scattered needlessly.
     */
    fun insert(type: ItemType, amount: Long): Long {
        if (!isOnline || amount <= 0L)
            return amount

        return StorageLock.withLock { Routing.insert(providers, type, amount) }
    }

    /**
     * Removes up to [amount] items of [type] and returns how many were actually removed.
     *
     * Extraction walks priorities in reverse so that low priority "overflow" providers drain first.
     */
    fun extract(type: ItemType, amount: Long): Long {
        if (!isOnline || amount <= 0L)
            return 0L

        return StorageLock.withLock { Routing.extract(providers, type, amount) }
    }

    /**
     * The total amount of [type] stored across the whole system.
     */
    fun countOf(type: ItemType): Long {
        if (!isOnline)
            return 0L

        return StorageLock.withLock { providers.sumOf { it.countOf(type) } }
    }

    /**
     * How much of [type] the system would actually hand over, which is what anything promising items to
     * somebody else has to ask — see [StorageProvider.extractableCountOf].
     */
    fun extractableCountOf(type: ItemType): Long {
        if (!isOnline)
            return 0L

        return StorageLock.withLock { providers.sumOf { it.extractableCountOf(type) } }
    }

    /**
     * Whether the system has room for at least one more item of some type.
     *
     * Deliberately type-agnostic and cheap: it only compares totals, so it can be called from the item
     * network tick without walking cell contents.
     */
    fun hasFreeSpace(): Boolean {
        if (!isOnline)
            return false

        return StorageLock.withLock { providers.any(StorageProvider::hasRoom) }
    }

    /**
     * Whether the system holds nothing at all.
     */
    fun isEmpty(): Boolean {
        if (!isOnline)
            return true

        return StorageLock.withLock { providers.all { it.usedCount == 0L } }
    }

    /**
     * An aggregated view of everything the system holds, for terminals and interfaces.
     */
    fun snapshot(): Map<ItemType, Long> {
        if (!isOnline)
            return emptyMap()

        return StorageLock.withLock {
            val index = HashMap<ItemType, Long>()
            for (provider in providers)
                provider.collectInto(index)
            index
        }
    }

    /**
     * The same view, restricted to what the system would actually hand over. This is the one an
     * *interface* offers to Nova's item network; [snapshot] is the one a terminal shows.
     */
    fun extractableSnapshot(): Map<ItemType, Long> {
        if (!isOnline)
            return emptyMap()

        return StorageLock.withLock {
            val index = HashMap<ItemType, Long>()
            for (provider in providers)
                provider.collectExtractableInto(index)
            index
        }
    }

    //</editor-fold>

    //<editor-fold desc="fluid operations", defaultstate="collapsed">

    /**
     * Stores up to [amount] of [type] and returns how much was left over.
     *
     * Same ordering as items: high priority first, and within a pass providers that already hold this
     * fluid before empty ones, so a bucket does not get split across two half-empty cells.
     */
    fun insertFluid(type: FluidType, amount: Long): Long {
        if (!isOnline || amount <= 0L)
            return amount

        return StorageLock.withLock { Routing.insertFluid(fluidProviders, type, amount) }
    }

    /**
     * Removes up to [amount] of [type] and returns how much was actually removed.
     */
    fun extractFluid(type: FluidType, amount: Long): Long {
        if (!isOnline || amount <= 0L)
            return 0L

        return StorageLock.withLock { Routing.extractFluid(fluidProviders, type, amount) }
    }

    fun amountOf(type: FluidType): Long {
        if (!isOnline)
            return 0L

        return StorageLock.withLock { fluidProviders.sumOf { it.amountOf(type) } }
    }

    /**
     * How much of [type] the system would actually hand over — see [StorageProvider.extractableCountOf].
     */
    fun extractableAmountOf(type: FluidType): Long {
        if (!isOnline)
            return 0L

        return StorageLock.withLock { fluidProviders.sumOf { it.extractableAmountOf(type) } }
    }

    /**
     * How many end points offer this system's fluids to a foreign fluid network.
     *
     * Fixed for the lifetime of the group, because the end points are: gaining or losing one is a
     * topology change, and Nova rebuilds us for those. See [FluidGateway] for what it is counted for.
     */
    val fluidGateways: Int = endPoints.count { it is FluidGateway }

    /**
     * Whether the system has room for at least one more unit of fluid.
     */
    fun hasFreeFluidSpace(): Boolean {
        if (!isOnline)
            return false

        return StorageLock.withLock { fluidProviders.any(FluidProvider::hasFluidRoom) }
    }

    fun fluidSnapshot(): Map<FluidType, Long> {
        if (!isOnline)
            return emptyMap()

        return StorageLock.withLock {
            val index = EnumMap<FluidType, Long>(FluidType::class.java)
            for (provider in fluidProviders)
                provider.collectFluidsInto(index)
            index
        }
    }

    /**
     * How much fluid the system could hold in total, which is what a terminal shows against what it
     * actually holds. Walks every provider, so it is computed on request rather than per tick.
     */
    /**
     * The access points on this system, for a wireless terminal deciding whether it is close enough to
     * one. Computed from the end point list rather than kept, because it is asked once per window open.
     */
    fun wirelessNodes(): List<WirelessNode> =
        if (isOnline) endPoints.filterIsInstance<WirelessNode>() else emptyList()

    fun fluidCapacity(): Long {
        if (!isOnline)
            return 0L

        return StorageLock.withLock { fluidProviders.sumOf(FluidProvider::totalAmount) }
    }

    //</editor-fold>

    override fun toString(): String =
        "StorageNetworkGroup(networks=${networks.size}, devices=${endPoints.size})"

}
