package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.registry.BlockStateProperties
import it.sgdc3.smartstorage.registry.NetworkTypes
import it.sgdc3.smartstorage.util.boxTowards
import it.sgdc3.smartstorage.util.destructionHitbox
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.joml.Vector3d
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.toEnumSet
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.context.intention.BlockPlace
import xyz.xenondevs.nova.util.CubeFaceSet
import xyz.xenondevs.nova.util.add
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.block.NovaBlockState
import xyz.xenondevs.nova.world.block.hitbox.Hitbox
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

    private var hitboxes: Set<Hitbox<*, *>> = emptySet()

    override fun handleEnable() {
        super.handleEnable()
        isValid = true
    }

    override fun handleDisable() {
        super.handleDisable()
        hitboxes.forEach { it.remove() }
        hitboxes = emptySet()
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

    override suspend fun handleNetworkLoaded(state: NetworkState) {
        // the persisted block state is already correct on load, only the hitboxes need rebuilding
        applyConnections(state, withBlockState = false)
    }

    override suspend fun handleNetworkUpdate(state: NetworkState) {
        applyConnections(state, withBlockState = true)
    }

    private suspend fun applyConnections(state: NetworkState, withBlockState: Boolean) {
        val connected = state.getConnectedNodes(this).columnKeySet().toEnumSet()
        val newBlockState = calculateBlockState(connected)
        val newHitboxes = createHitboxes(connected)

        runTask {
            if (!isEnabled)
                return@runTask

            if (withBlockState)
                updateBlockState(newBlockState)
            replaceHitboxes(newHitboxes)
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

    /**
     * The cable's vanilla backing block has no collision, so without these it could not be mined at all.
     * One box for the core plus one per connected arm, matching the generated model.
     */
    private fun createHitboxes(connected: Set<BlockFace>): Set<Hitbox<*, *>> {
        val hitboxes = HashSet<Hitbox<*, *>>()

        hitboxes += destructionHitbox(
            block,
            block.location.add(Vector3d(0.375, 0.375, 0.375)),
            block.location.add(Vector3d(0.625, 0.625, 0.625))
        )

        for (face in connected) {
            val (from, to) = block.boxTowards(Vector3d(0.42, 0.42, 0.5), Vector3d(0.58, 0.58, 1.0), face)
            hitboxes += destructionHitbox(block, from, to)
        }

        return hitboxes
    }

    private fun replaceHitboxes(hitboxes: Set<Hitbox<*, *>>) {
        this.hitboxes.forEach { it.remove() }
        this.hitboxes = hitboxes
        this.hitboxes.forEach { it.register() }
    }

    override fun handleTick() = Unit

}
