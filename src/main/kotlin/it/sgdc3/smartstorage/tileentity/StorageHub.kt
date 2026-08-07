package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.registry.BlockStateProperties
import it.sgdc3.smartstorage.util.boxTowards
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.joml.Math
import org.joml.Quaternionf
import org.joml.Vector3d
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.toEnumSet
import xyz.xenondevs.nova.util.add
import xyz.xenondevs.nova.util.pitch
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.hitbox.Hitbox
import xyz.xenondevs.nova.world.block.hitbox.VirtualHitbox
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.model.FixedMultiModel
import xyz.xenondevs.nova.world.model.Model

/**
 * The shape shared by the two devices that mount onto their neighbours instead of standing beside them:
 * a core that grows an arm towards every device on the storage network, and a port against every side it
 * serves. The same shape Nova's own pipes take, and drawn the same way.
 *
 * Arms are block state, because a network connection is something Nova hands us as an update anyway.
 * Ports are display entities, because what a hub serves is *not* block state — a chest can be placed or
 * broken beside a connector without the connector's own state changing, and there would be nothing to
 * hang a block state update on. Nova renders its pipe attachments as display entities for that reason.
 *
 * Neither is a solid block: the vanilla state behind the model is a chain where the hub runs straight
 * through and a structure void otherwise, so a hub takes up as much room as a cable and is still mined
 * and right-clicked like any other block. The only hitboxes built here are the ports, and only to catch
 * a right-click — a port opens the configuration for the one side it serves, instead of the menu for the
 * whole hub.
 */
abstract class StorageHub(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(pos, state, data) {

    /**
     * The hidden items whose models a port is drawn with, lit and dark, from
     * [it.sgdc3.smartstorage.registry.Models]. A display entity carries one model and cannot pick a
     * texture, so the two states are two items.
     */
    protected abstract val portModel: NovaItem
    protected abstract val portModelOff: NovaItem

    private val multiModel = FixedMultiModel()
    private var portFaces: Set<BlockFace> = emptySet()
    private var armFaces: Set<BlockFace> = emptySet()
    private var powered: Boolean = false
    private var hitboxes: Set<Hitbox<*, *>> = emptySet()

    override fun handleEnable() {
        super.handleEnable()
        // the persisted block state already knows where the arms are; the hitboxes are what needs
        // rebuilding, and they have to exist before the first network update or the block is untouchable
        armFaces = armFacesFromBlockState()
        powered = blockState[BlockStateProperties.POWERED] == true
        rebuildHitboxes()
    }

    /**
     * Lights the core and the ports, or puts them out. Main thread only: it swaps display entities.
     *
     * The core is block state and the ports are display entities, so one switch has to reach two
     * different mechanisms — which is the same split the arms and the ports already live on.
     */
    protected fun setPowered(powered: Boolean) {
        if (powered == this.powered)
            return

        this.powered = powered
        applyBlockState()
        applyPortModels()
    }

    override fun handleDisable() {
        multiModel.clear()
        hitboxes.forEach(Hitbox<*, *>::remove)
        hitboxes = emptySet()
        portFaces = emptySet()
        armFaces = emptySet()
        super.handleDisable()
    }

    /**
     * Opens the configuration for the one side this port serves. Right-clicking anywhere else on the hub
     * opens its main menu.
     */
    protected open fun openPortMenu(player: Player, face: BlockFace) {
        menuContainer.openWindow(player)
    }

    //<editor-fold desc="arms", defaultstate="collapsed">

    /**
     * Points an arm at each of [faces]. Main thread only.
     *
     * Mirrors what [StorageCable] does with the same properties and the same model geometry, so a hub's
     * arms meet a cable's without a seam.
     */
    protected fun setArmFaces(faces: Set<BlockFace>) {
        if (faces == armFaces)
            return

        armFaces = faces.toEnumSet()
        applyBlockState()
    }

    /**
     * The six booleans say which sides are *occupied*, not which ones carry an arm, and the difference
     * only shows up in the block behind the model: it lays a chain along an axis whose two ends are both
     * taken, and a port fills its side of an axis just as well as an arm does.
     *
     * Drawing the extra arm costs nothing, because an arm towards a port is swallowed whole by the neck
     * and the flange in front of it — same axis, and 2.5 units across against the neck's 4.
     */
    private fun applyBlockState() {
        val occupied = armFaces + portFaces
        updateBlockState(
            block.defaultBlockState.with(
                mapOf(
                    BlockStateProperties.NORTH to (BlockFace.NORTH in occupied),
                    BlockStateProperties.EAST to (BlockFace.EAST in occupied),
                    BlockStateProperties.SOUTH to (BlockFace.SOUTH in occupied),
                    BlockStateProperties.WEST to (BlockFace.WEST in occupied),
                    BlockStateProperties.UP to (BlockFace.UP in occupied),
                    BlockStateProperties.DOWN to (BlockFace.DOWN in occupied),
                    BlockStateProperties.POWERED to powered
                )
            )
        )
    }

    /**
     * Seeds [armFaces] so that the first [applyBlockState] does not blank the arms the world already
     * knows about. It reads back the union, ports included, but the first network update corrects that.
     */
    private fun armFacesFromBlockState(): Set<BlockFace> {
        val faces = HashSet<BlockFace>()
        if (blockState[BlockStateProperties.NORTH] == true) faces += BlockFace.NORTH
        if (blockState[BlockStateProperties.EAST] == true) faces += BlockFace.EAST
        if (blockState[BlockStateProperties.SOUTH] == true) faces += BlockFace.SOUTH
        if (blockState[BlockStateProperties.WEST] == true) faces += BlockFace.WEST
        if (blockState[BlockStateProperties.UP] == true) faces += BlockFace.UP
        if (blockState[BlockStateProperties.DOWN] == true) faces += BlockFace.DOWN
        return faces.toEnumSet()
    }

    //</editor-fold>

    //<editor-fold desc="ports", defaultstate="collapsed">

    /**
     * Replaces the sides showing a port and answers whether anything actually changed, so a caller can
     * skip the follow-up work when it did not. Main thread only: it spawns and despawns display entities.
     */
    protected fun setPortFaces(faces: Set<BlockFace>): Boolean {
        if (faces == portFaces)
            return false

        portFaces = faces.toEnumSet()
        applyPortModels()
        rebuildHitboxes()
        applyBlockState()
        return true
    }

    private fun applyPortModels() {
        val item = if (powered) portModel else portModelOff

        multiModel.replaceModels(
            portFaces.mapTo(HashSet()) { face ->
                Model(
                    item.createClientsideItemBuilder().get(),
                    pos.location.add(0.5, 0.5, 0.5),
                    // the port model is authored pointing south while a display entity turns a model's
                    // north side south, so an unrotated port already points north — this is the
                    // difference from there
                    leftRotation = Quaternionf()
                        .rotateY(Math.toRadians(180.0 - face.yaw).toFloat())
                        .rotateX(Math.toRadians(-face.pitch.toDouble()).toFloat())
                )
            }
        )
    }

    //</editor-fold>

    /**
     * One box per port, matching the flange in the model — its own numbers, divided by 16.
     *
     * Right-click only. Breaking is left to the chain behind the model so that hardness and tool still
     * mean something, which a virtual hitbox cannot do: Minecraft runs its mining timer against real
     * blocks, so a hitbox that breaks on click breaks instantly.
     */
    private fun rebuildHitboxes() {
        hitboxes.forEach(Hitbox<*, *>::remove)

        val boxes = portFaces.mapTo(HashSet<Hitbox<*, *>>()) { face ->
            val (from, to) = pos.boxTowards(Vector3d(0.125, 0.125, 0.875), Vector3d(0.875, 0.875, 1.0), face)
            VirtualHitbox(from, to).apply {
                addRightClickHandler { player, _ -> openPortMenu(player, face) }
            }
        }

        hitboxes = boxes
        boxes.forEach(Hitbox<*, *>::register)
    }

}
