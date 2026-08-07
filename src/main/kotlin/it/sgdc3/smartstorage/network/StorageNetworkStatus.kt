package it.sgdc3.smartstorage.network

/**
 * Why a storage network is not currently operational.
 */
enum class OfflineReason(val localizationKey: String) {

    NO_CONTROLLER("menu.smartstorage.status.no_controller"),
    MULTIPLE_CONTROLLERS("menu.smartstorage.status.multiple_controllers"),
    TOO_MANY_DEVICES("menu.smartstorage.status.too_many_devices"),
    NO_ENERGY("menu.smartstorage.status.no_energy"),

    /**
     * The device is holding a network that no longer exists — see [StorageNetworkGroup.lastTick]. Not a
     * state the network itself ever reports, only one a device works out about itself.
     */
    DISCONNECTED("menu.smartstorage.status.disconnected")

}

/**
 * An immutable snapshot of a storage network, recomputed once per network tick and handed to the
 * controller so menus can render it without touching network internals from the main thread.
 *
 * Deliberately cheap. How full the network is lives in [StorageTotals] instead, because working that
 * out means walking every cell and container and only one menu in the addon ever shows it.
 */
data class StorageNetworkStatus(
    val offlineReason: OfflineReason?,
    val devices: Int,
    val cells: Int
) {

    val isOnline: Boolean
        get() = offlineReason == null

    companion object {

        fun offline(reason: OfflineReason, devices: Int = 0, cells: Int = 0) =
            StorageNetworkStatus(reason, devices, cells)

    }

}

/**
 * How much of a storage network is in use.
 *
 * Computed on request rather than per tick: it walks every provider, and the controller's status icon
 * is the only thing that reads it. Asking for it from the main thread while a menu is open costs the
 * same walk the network tick used to do unconditionally, for every network, whether or not anyone had
 * a menu open at all.
 */
data class StorageTotals(
    val usedTypes: Int,
    val totalTypes: Int,
    val usedCount: Long,
    val totalCount: Long,
    /** Nova's fluid units, 1000 to the bucket. */
    val usedFluid: Long,
    val totalFluid: Long
) {

    /**
     * Whether the system has any fluid capacity at all. A network with no fluid cells and no tanks
     * should not be told it holds 0 of 0 buckets — it should not be told anything.
     */
    val hasFluidStorage: Boolean
        get() = totalFluid > 0L

    companion object {

        val EMPTY = StorageTotals(0, 0, 0L, 0L, 0L, 0L)

    }

}
