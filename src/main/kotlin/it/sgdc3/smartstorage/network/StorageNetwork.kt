package it.sgdc3.smartstorage.network

import it.sgdc3.smartstorage.storage.ItemType
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.world.block.tileentity.network.Network
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkData
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType

/**
 * One stretch of storage network: the devices reachable from each other over a single run of cable, or
 * simply placed against each other.
 *
 * This is *not* the whole system. Only a cable bridges a network, so a device sitting between two cable
 * runs belongs to both, and Nova groups every network that shares a node into one cluster. The aggregate
 * — status, providers, energy — lives in [StorageNetworkGroup]; this class collects the local members,
 * publishes itself to them, and forwards everything else there.
 *
 * Instances are transient: Nova rebuilds them whenever the topology changes, so all persistent state
 * lives in the tile-entities.
 */
class StorageNetwork internal constructor(
    networkData: NetworkData<StorageNetwork>
) : Network<StorageNetwork>, NetworkData<StorageNetwork> by networkData {

    internal val endPoints: List<StorageEndPoint>

    /**
     * Set by [StorageNetworkGroup] as it is built, which Nova does right after the networks themselves.
     * Until then there is nothing to aggregate over and the system reads as offline — which is exactly
     * what it did before its first tick anyway.
     */
    @Volatile
    internal var group: StorageNetworkGroup? = null

    val status: StorageNetworkStatus
        get() = group?.status ?: StorageNetworkStatus.offline(OfflineReason.NO_CONTROLLER)

    val isOnline: Boolean
        get() = group?.isOnline == true

    init {
        val endPoints = ArrayList<StorageEndPoint>()
        for ((_, con) in nodes) {
            val node = con.node
            if (node is StorageEndPoint)
                endPoints += node
        }

        this.endPoints = endPoints

        // publish this network to its members; they clear the reference themselves when broken or unloaded
        for (endPoint in endPoints)
            endPoint.storageNetwork = this
    }

    override fun isValid(): Boolean =
        endPoints.all { it.isValid }

    //<editor-fold desc="storage operations, all of them the group's", defaultstate="collapsed">

    /**
     * Stores up to [amount] items of [type] and returns how many were left over.
     */
    fun insert(type: ItemType, amount: Long): Long =
        group?.insert(type, amount) ?: amount

    /**
     * Removes up to [amount] items of [type] and returns how many were actually removed.
     */
    fun extract(type: ItemType, amount: Long): Long =
        group?.extract(type, amount) ?: 0L

    /**
     * The total amount of [type] stored across the whole system.
     */
    fun countOf(type: ItemType): Long =
        group?.countOf(type) ?: 0L

    /**
     * How much of [type] the system would actually hand over — see
     * [StorageProvider.extractableCountOf].
     */
    fun extractableCountOf(type: ItemType): Long =
        group?.extractableCountOf(type) ?: 0L

    /**
     * Whether the system has room for at least one more item of some type.
     */
    fun hasFreeSpace(): Boolean =
        group?.hasFreeSpace() == true

    /**
     * Whether the system holds nothing at all.
     */
    fun isEmpty(): Boolean =
        group?.isEmpty() != false

    /**
     * An aggregated view of everything the system holds, for terminals and interfaces.
     */
    fun snapshot(): Map<ItemType, Long> =
        group?.snapshot() ?: emptyMap()

    /**
     * The same view, restricted to what the system would actually hand over — see
     * [StorageNetworkGroup.extractableSnapshot].
     */
    fun extractableSnapshot(): Map<ItemType, Long> =
        group?.extractableSnapshot() ?: emptyMap()

    /**
     * How full the system is, for the controller's status icon. Computed on request — see
     * [StorageNetworkGroup.totals].
     */
    fun totals(): StorageTotals =
        group?.totals() ?: StorageTotals.EMPTY

    //</editor-fold>

    //<editor-fold desc="fluid operations, all of them the group's", defaultstate="collapsed">

    fun insertFluid(type: FluidType, amount: Long): Long =
        group?.insertFluid(type, amount) ?: amount

    fun extractFluid(type: FluidType, amount: Long): Long =
        group?.extractFluid(type, amount) ?: 0L

    fun amountOf(type: FluidType): Long =
        group?.amountOf(type) ?: 0L

    /**
     * How much of [type] the system would actually hand over, divided by the number of end points
     * promising it — see [NetworkFluidView.amount].
     */
    fun promisableAmountOf(type: FluidType): Long {
        val group = group ?: return 0L
        val extractable = group.extractableAmountOf(type)

        // Rounded down, and the remainder deliberately left behind: the point of the division is that
        // the gateways' promises must not add up to more than the system holds, and under-promising by
        // a few units costs nothing while over-promising by one creates fluid.
        val gateways = group.fluidGateways
        return if (gateways <= 1) extractable else extractable / gateways
    }

    fun hasFreeFluidSpace(): Boolean =
        group?.hasFreeFluidSpace() == true

    fun fluidSnapshot(): Map<FluidType, Long> =
        group?.fluidSnapshot() ?: emptyMap()

    fun fluidCapacity(): Long =
        group?.fluidCapacity() ?: 0L

    //</editor-fold>

    /**
     * The access points on this system — see [StorageNetworkGroup.wirelessNodes].
     */
    fun wirelessNodes(): List<WirelessNode> =
        group?.wirelessNodes() ?: emptyList()

    override fun toString(): String =
        "StorageNetwork(uuid=$uuid, devices=${endPoints.size})"

    companion object {

        /**
         * Any two storage end points touching each other are connected — the network has no per-face
         * configuration, so there is nothing that could make a local connection invalid.
         */
        fun validateLocal(from: NetworkEndPoint, to: NetworkEndPoint, face: BlockFace): Boolean = true

    }

}
