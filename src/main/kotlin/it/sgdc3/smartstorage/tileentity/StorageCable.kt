package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.registry.BlockStateProperties
import it.sgdc3.smartstorage.registry.NetworkTypes
import it.sgdc3.smartstorage.util.boxTowards
import it.sgdc3.smartstorage.util.destructionHitbox
import org.bukkit.block.BlockFace
import org.joml.Vector3d
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.toEnumSet
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.context.intention.BlockPlace
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.add
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.hitbox.Hitbox
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkBridge
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.format.NetworkState

private val SUPPORTED_NETWORK_TYPES: Set<NetworkType<*>> = hashSetOf(NetworkTypes.STORAGE)

/**
 * Carries a storage network between devices that are not placed against each other.
 *
 * Deliberately plainer than Nova's item/energy cables: a storage network has no per-face configuration
 * and no throughput, so there is nothing to configure and nothing to tick.
 */
class StorageCable(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : TileEntity(pos, state, data), NetworkBridge {

    @Volatile
    override var isValid = false

    override val linkedNodes: Set<NetworkNode> = emptySet()
    override val typeId get() = block.id

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
        NetworkManager.queueAddBridge(this, SUPPORTED_NETWORK_TYPES, CUBE_FACES)
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
        block.defaultBlockState.with(
            mapOf(
                BlockStateProperties.NORTH to (BlockFace.NORTH in connected),
                BlockStateProperties.EAST to (BlockFace.EAST in connected),
                BlockStateProperties.SOUTH to (BlockFace.SOUTH in connected),
                BlockStateProperties.WEST to (BlockFace.WEST in connected),
                BlockStateProperties.UP to (BlockFace.UP in connected),
                BlockStateProperties.DOWN to (BlockFace.DOWN in connected)
            )
        )

    /**
     * The cable's vanilla backing block has no collision, so without these it could not be mined at all.
     * One box for the core plus one per connected arm, matching the generated model.
     */
    private fun createHitboxes(connected: Set<BlockFace>): Set<Hitbox<*, *>> {
        val hitboxes = HashSet<Hitbox<*, *>>()

        hitboxes += destructionHitbox(
            pos,
            pos.location.add(Vector3d(0.375, 0.375, 0.375)),
            pos.location.add(Vector3d(0.625, 0.625, 0.625))
        )

        for (face in connected) {
            val (from, to) = pos.boxTowards(Vector3d(0.42, 0.42, 0.5), Vector3d(0.58, 0.58, 1.0), face)
            hitboxes += destructionHitbox(pos, from, to)
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
