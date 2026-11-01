package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.registry.BlockStateProperties
import it.sgdc3.smartstorage.registry.NetworkTypes
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.toEnumSet
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.context.intention.BlockPlace
import xyz.xenondevs.nova.util.CubeFaceSet
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.block.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkBridge
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.format.NetworkState

// lazy: a registry entry can only be resolved once the registries are frozen
private val SUPPORTED_NETWORK_TYPES: Set<NetworkType<*>> by lazy { setOf(NetworkTypes.STORAGE.get()) }

/**
 * Carries a storage network between devices that are not placed against each other.
 *
 * Deliberately plainer than Nova's item/energy cables: a storage network has no per-face configuration
 * and no throughput, so there is nothing to configure and nothing to tick.
 */
class StorageCable(
    block: Block,
    state: NovaBlockState,
    data: Compound
) : TileEntity(block, state, data), NetworkBridge {

    @Volatile
    override var isValid = false

    override val linkedNodes: Set<NetworkNode> = emptySet()
    override val typeId get() = blockType.key

    override fun handleEnable() {
        super.handleEnable()
        isValid = true
    }

    override fun handleDisable() {
        super.handleDisable()
        isValid = false
    }

    override fun handlePlace(ctx: Context<BlockPlace>) {
        super.handlePlace(ctx)
        NetworkManager.queueAddBridge(this, SUPPORTED_NETWORK_TYPES, CubeFaceSet.ALL)
        isValid = true
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        super.handleBreak(ctx)
        NetworkManager.queueRemoveBridge(this)
        isValid = false
    }

    /**
     * Only an update needs handling: the block state a cable is loaded with is the one it was saved
     * with, and its hitboxes are Nova's, drawn from that state.
     */
    override suspend fun handleNetworkUpdate(state: NetworkState) {
        val connected = state.getConnectedNodes(this).columnKeySet().toEnumSet()
        val newBlockState = calculateBlockState(connected)

        runTask {
            if (isEnabled)
                updateBlockState(newBlockState)
        }
    }

    private fun calculateBlockState(connected: Set<BlockFace>): NovaBlockState =
        blockState.apply {
            this[BlockStateProperties.NORTH] = BlockFace.NORTH in connected
            this[BlockStateProperties.EAST] = BlockFace.EAST in connected
            this[BlockStateProperties.SOUTH] = BlockFace.SOUTH in connected
            this[BlockStateProperties.WEST] = BlockFace.WEST in connected
            this[BlockStateProperties.UP] = BlockFace.UP in connected
            this[BlockStateProperties.DOWN] = BlockFace.DOWN in connected
        }

    override fun handleTick() = Unit

}
