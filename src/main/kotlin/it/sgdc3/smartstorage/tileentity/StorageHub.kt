package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.registry.BlockStateProperties
import it.sgdc3.smartstorage.util.ItemDisplays
import it.sgdc3.smartstorage.util.boxTowards
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemType
import org.joml.Math
import org.joml.Quaternionf
import org.joml.Vector3d
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.toEnumSet
import xyz.xenondevs.nova.registry.RegistryEntry
import xyz.xenondevs.nova.util.add
import xyz.xenondevs.nova.util.pitch
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.world.block.NovaBlockState
import xyz.xenondevs.nova.world.block.hitbox.Hitbox
import xyz.xenondevs.nova.world.block.hitbox.VirtualHitbox
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity

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
    block: Block,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(block, state, data) {

    /**
     * The hidden items whose models a port is drawn with, lit and dark, from
     * [it.sgdc3.smartstorage.registry.Models]. A display entity carries one model and cannot pick a
     * texture, so the two states are two items.
     *
     * Null for a hub that mounts onto nothing — a valve in a cable run has arms and no ports, and
     * inventing an attachment it never draws would be a worse answer than saying it has none.
     */
    protected open val portModel: RegistryEntry.Paper<ItemType>? = null
    protected open val portModelOff: RegistryEntry.Paper<ItemType>? = null

    /**
     * The hub's own core, lit and dark, when it is drawn as a display entity rather than as part of the
     * block model.
     *
     * Null leaves it to the block model, which is what the energy valve does — its arms are the entities
     * and its core is the one thing left in the model.
     *
     * The others go the other way, and the saving is the reason. A hub's arms are the same shape as a
     * cable's, deliberately and to the unit, and its core is an eight-cube that swallows the cable's
     * four-cube whole. So a hub can simply *be* a cable as far as the block model is concerned, and wear
     * its own core on top — which is sixty-four models shared between five blocks instead of a hundred
     * and twenty-eight apiece.
     */
    protected open val coreModel: RegistryEntry.Paper<ItemType>? = null
    protected open val coreModelOff: RegistryEntry.Paper<ItemType>? = null

    /**
     * Keyed by the side a port points at, and by [Core] for the core.
     */
    private val displays = ItemDisplays<Any> { block.location.add(0.5, 0.5, 0.5) }
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
        // A hub whose core is a display entity has nothing to show until this runs, and a hub with no
        // ports would otherwise wait for a network update that has no reason to come.
        applyPortModels()
    }

    /**
     * Lights the core and the ports, or puts them out. Main thread only: it swaps display entities.
     *
     * One switch, two mechanisms, because a hub's core is block state for some and a display entity for
     * others — see [coreModel]. It reaches both rather than asking which kind this is.
     *
     * @return whether anything changed, so a caller can redraw the menu that says the same thing.
     */
    protected fun setPowered(powered: Boolean): Boolean {
        if (powered == this.powered)
            return false

        this.powered = powered
        applyBlockState()
        applyPortModels()
        return true
    }

    override fun handleDisable() {
        displays.clear()
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
        menu.open(player)
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
            blockState.apply {
                this[BlockStateProperties.NORTH] = BlockFace.NORTH in occupied
                this[BlockStateProperties.EAST] = BlockFace.EAST in occupied
                this[BlockStateProperties.SOUTH] = BlockFace.SOUTH in occupied
                this[BlockStateProperties.WEST] = BlockFace.WEST in occupied
                this[BlockStateProperties.UP] = BlockFace.UP in occupied
                this[BlockStateProperties.DOWN] = BlockFace.DOWN in occupied
                this[BlockStateProperties.POWERED] = powered
            }
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

    /**
     * The model to draw on [face], or null to draw none.
     *
     * Defaults to the lit or dark [portModel] according to whether the hub as a whole is running, which
     * is what a connector and an interface want: their ports are all alike because the thing that varies
     * is the hub. A valve overrides it, because its sides are decided one at a time.
     */
    protected open fun portModelFor(face: BlockFace): RegistryEntry.Paper<ItemType>? =
        if (powered) portModel else portModelOff

    /**
     * Redraws the ports without anything else having changed.
     *
     * [setPowered] and [setPortFaces] already do it when they change something, and for the other hubs
     * that is every occasion there is. It is not for a hub whose ports differ from each other: the set of
     * sides can stay the same, and the hub stay lit, while which of those sides is being served changes.
     */
    protected fun refreshPortModels() = applyPortModels()

    private fun applyPortModels() {
        val models = HashMap<Any, ItemDisplays.Model>()
        for (face in portFaces) {
            val item = portModelFor(face) ?: continue
            models[face] = ItemDisplays.Model(
                item.get(),
                // the port model is authored pointing south while a display entity turns a model's
                // north side south, so an unrotated port already points north — this is the
                // difference from there
                Quaternionf()
                    .rotateY(Math.toRadians(180.0 - face.yaw).toFloat())
                    .rotateX(Math.toRadians(-face.pitch.toDouble()).toFloat())
            )
        }

        // unrotated: a core is a cube in the middle and looks the same whichever way it is turned
        (if (powered) coreModel else coreModelOff)?.let { core ->
            models[Core] = ItemDisplays.Model(core.get())
        }

        displays.show(models)
    }

    //</editor-fold>

    /**
     * One box per port, matching the flange in the model — its own numbers, divided by 16.
     *
     * Right-click only, and virtual because which sides are ports is not block state, so the native
     * hitboxes Nova builds from block state cannot know. Breaking goes through those — the core and the
     * arms, see [it.sgdc3.smartstorage.registry.Blocks] — because a virtual hitbox cannot run Minecraft's
     * mining timer and would break the hub instantly.
     */
    private fun rebuildHitboxes() {
        hitboxes.forEach(Hitbox<*, *>::remove)

        val boxes = portFaces.mapTo(HashSet<Hitbox<*, *>>()) { face ->
            val (from, to) = block.boxTowards(Vector3d(0.125, 0.125, 0.875), Vector3d(0.875, 0.875, 1.0), face)
            VirtualHitbox(from, to).apply {
                addRightClickHandler { player, _ -> openPortMenu(player, face) }
            }
        }

        hitboxes = boxes
        boxes.forEach(Hitbox<*, *>::register)
    }

}

/**
 * The key a hub's core is drawn under, beside the six sides its ports are drawn under.
 */
private object Core
