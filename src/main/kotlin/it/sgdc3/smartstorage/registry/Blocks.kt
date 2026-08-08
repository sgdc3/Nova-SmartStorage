@file:Suppress("unused")

package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage.tileEntity
import it.sgdc3.smartstorage.tileentity.BarrelController
import it.sgdc3.smartstorage.tileentity.CraftingTerminal
import it.sgdc3.smartstorage.tileentity.DriveBay
import it.sgdc3.smartstorage.tileentity.FluidConnector
import it.sgdc3.smartstorage.tileentity.FluidInterface
import it.sgdc3.smartstorage.tileentity.FluidTerminal
import it.sgdc3.smartstorage.tileentity.StorageBarrel
import it.sgdc3.smartstorage.tileentity.StorageCable
import it.sgdc3.smartstorage.tileentity.StorageConnector
import it.sgdc3.smartstorage.tileentity.StorageController
import it.sgdc3.smartstorage.tileentity.StorageInterface
import it.sgdc3.smartstorage.tileentity.StorageTerminal
import it.sgdc3.smartstorage.tileentity.WirelessAccessPoint
import it.sgdc3.smartstorage.util.encodeFlags
import net.minecraft.core.Direction.Axis
import org.bukkit.Material
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.resources.builder.layout.block.BackingStateCategory
import xyz.xenondevs.nova.world.block.NovaTileEntityBlock
import xyz.xenondevs.nova.world.block.TileEntityConstructor
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.behavior.TileEntityLimited
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultScopedBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.ScopedBlockStateProperty
import xyz.xenondevs.nova.world.item.tool.VanillaToolCategories
import xyz.xenondevs.nova.world.item.tool.VanillaToolTiers
import net.minecraft.world.level.block.Blocks as MojangBlocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties as MojangBlockStateProperties

@Init(stage = InitStage.PRE_PACK)
object Blocks {

    // Declared before the blocks that use them: property initializers of an object run top to bottom,
    // so a behavior referenced further down would still be null when the block is built.
    private val CABLE = Breakable(0.0, requiresToolForDrops = false)

    /**
     * Shared by every machine-like block of this addon.
     */
    private val DEVICE = Breakable(4.0, setOf(VanillaToolCategories.PICKAXE), VanillaToolTiers.STONE, true, Material.IRON_BLOCK)

    /**
     * The barrel's own, identical to [DEVICE] but for the one flag: it drops whatever it was broken
     * with, bare hands included.
     *
     * Every other block here is worth the iron it is made of, so losing one to the wrong pickaxe is the
     * ordinary cost of mining without the right tool. A barrel is not: it carries thousands of items in
     * its own data — see [it.sgdc3.smartstorage.tileentity.StorageBarrel.getDrops] — and "wrong tool"
     * would quietly destroy all of them along with the block. A pickaxe still mines it faster; it just
     * no longer decides whether the contents survive.
     */
    private val BARREL = Breakable(4.0, setOf(VanillaToolCategories.PICKAXE), VanillaToolTiers.STONE, false, Material.IRON_BLOCK)

    val STORAGE_CABLE: NovaTileEntityBlock = tileEntity("storage_cable", ::StorageCable) {
        tickrate(0)
        behaviors(TileEntityLimited, TileEntityDrops, CABLE, BlockSounds(SoundGroup.METAL))
        stateProperties(
            ScopedBlockStateProperties.NORTH,
            ScopedBlockStateProperties.EAST,
            ScopedBlockStateProperties.SOUTH,
            ScopedBlockStateProperties.WEST,
            ScopedBlockStateProperties.UP,
            ScopedBlockStateProperties.DOWN
        )

        entityBacked({
            // a chain gives straight runs a sensible collider; everything else relies on the virtual
            // hitboxes StorageCable registers, so the block itself must not block movement
            val north = getPropertyValueOrThrow(BlockStateProperties.NORTH)
            val east = getPropertyValueOrThrow(BlockStateProperties.EAST)
            val south = getPropertyValueOrThrow(BlockStateProperties.SOUTH)
            val west = getPropertyValueOrThrow(BlockStateProperties.WEST)
            val up = getPropertyValueOrThrow(BlockStateProperties.UP)
            val down = getPropertyValueOrThrow(BlockStateProperties.DOWN)

            when {
                east && west -> MojangBlocks.IRON_CHAIN.defaultBlockState()
                    .setValue(MojangBlockStateProperties.AXIS, Axis.X)

                north && south -> MojangBlocks.IRON_CHAIN.defaultBlockState()
                    .setValue(MojangBlockStateProperties.AXIS, Axis.Z)

                up && down -> MojangBlocks.IRON_CHAIN.defaultBlockState()
                    .setValue(MojangBlockStateProperties.AXIS, Axis.Y)

                else -> MojangBlocks.STRUCTURE_VOID.defaultBlockState()
            }
        }, {
            val id = encodeFlags(
                getPropertyValueOrThrow(BlockStateProperties.NORTH),
                getPropertyValueOrThrow(BlockStateProperties.EAST),
                getPropertyValueOrThrow(BlockStateProperties.SOUTH),
                getPropertyValueOrThrow(BlockStateProperties.WEST),
                getPropertyValueOrThrow(BlockStateProperties.UP),
                getPropertyValueOrThrow(BlockStateProperties.DOWN)
            )

            getModel("block/cable/$id")
        })
    }

    val STORAGE_CONTROLLER: NovaTileEntityBlock = device("storage_controller", ::StorageController)

    val DRIVE_BAY: NovaTileEntityBlock = facingDevice("drive_bay", ::DriveBay)
    val STORAGE_TERMINAL: NovaTileEntityBlock = facingDevice("storage_terminal", ::StorageTerminal)
    val CRAFTING_TERMINAL: NovaTileEntityBlock = facingDevice("crafting_terminal", ::CraftingTerminal)
    val FLUID_TERMINAL: NovaTileEntityBlock = facingDevice("fluid_terminal", ::FluidTerminal)

    /**
     * A facing device with a second state property, so the front can carry a padlock while the barrel is
     * locked onto its item.
     *
     * Written out rather than folded into [facingDevice] because it is the only block here whose model
     * depends on something other than which way it points, and hiding that behind a parameter would make
     * three simple blocks pay for one.
     */
    val STORAGE_BARREL: NovaTileEntityBlock = tileEntity("storage_barrel", ::StorageBarrel) {
        behaviors(TileEntityLimited, TileEntityDrops, TileEntityInteractive, BARREL, BlockSounds(SoundGroup.METAL))
        stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL, ScopedBlockStateProperties.LOCKED)
        stateBacked(BackingStateCategory.NOTE_BLOCK, BackingStateCategory.MUSHROOM_BLOCK) {
            val locked = getPropertyValueOrThrow(BlockStateProperties.LOCKED)
            getModel(if (locked) "block/storage_barrel_locked" else "block/storage_barrel").rotated()
        }
    }

    // nothing about what a controller does depends on which way it points; it has a front because a
    // screen belongs on the side you look at, and for no other reason
    val BARREL_CONTROLLER: NovaTileEntityBlock = facingDevice("barrel_controller", ::BarrelController)

    val WIRELESS_ACCESS_POINT: NovaTileEntityBlock = device("wireless_access_point", ::WirelessAccessPoint)

    val STORAGE_INTERFACE: NovaTileEntityBlock = hub("storage_interface", "interface", ::StorageInterface)
    val STORAGE_CONNECTOR: NovaTileEntityBlock = hub("storage_connector", "connector", ::StorageConnector)

    // the same two blocks for fluids: same shape, same six live sides, different thing flowing through
    val FLUID_INTERFACE: NovaTileEntityBlock = hub("fluid_interface", "fluid_interface", ::FluidInterface)
    val FLUID_CONNECTOR: NovaTileEntityBlock = hub("fluid_connector", "fluid_connector", ::FluidConnector)

    /**
     * The name of the model a device wears right now: the lit one, or the dark twin.
     *
     * Every device has both, because a device the controller is not keeping alive should not be blinking
     * — an animated light on a dead machine is the one thing a status readout must never do.
     */
    private fun deviceModel(name: String, powered: Boolean): String =
        if (powered) "block/$name" else "block/${name}_off"

    /**
     * A full-cube, right-clickable machine block that looks the same from every side.
     */
    private fun device(name: String, constructor: TileEntityConstructor): NovaTileEntityBlock =
        tileEntity(name, constructor) {
            behaviors(TileEntityLimited, TileEntityDrops, TileEntityInteractive, DEVICE, BlockSounds(SoundGroup.METAL))
            stateProperties(ScopedBlockStateProperties.POWERED)
            stateBacked(BackingStateCategory.NOTE_BLOCK, BackingStateCategory.MUSHROOM_BLOCK) {
                getModel(deviceModel(name, getPropertyValueOrThrow(BlockStateProperties.POWERED)))
            }
        }

    /**
     * A device whose front panel — screen, disk bays — only makes sense on the side the player faces.
     *
     * Nova orients a placed block so that FACING points back at whoever placed it, and `rotated()` is a
     * no-op for FACING=NORTH, so the model is authored with its front on the north face.
     */
    private fun facingDevice(name: String, constructor: TileEntityConstructor): NovaTileEntityBlock =
        tileEntity(name, constructor) {
            behaviors(TileEntityLimited, TileEntityDrops, TileEntityInteractive, DEVICE, BlockSounds(SoundGroup.METAL))
            stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL, ScopedBlockStateProperties.POWERED)
            stateBacked(BackingStateCategory.NOTE_BLOCK, BackingStateCategory.MUSHROOM_BLOCK) {
                getModel(deviceModel(name, getPropertyValueOrThrow(BlockStateProperties.POWERED))).rotated()
            }
        }

    /**
     * A device that mounts onto its neighbours instead of standing beside them: a core that grows an arm
     * towards every device on the storage network and a port against every side it serves — the shape
     * Nova's own pipes take where they meet a container. Neither of them has a facing; both are live on
     * all six sides. See [it.sgdc3.smartstorage.tileentity.StorageHub].
     *
     * The six booleans mark the occupied sides — an arm or a port. Only arms are drawn from them; the
     * ports are display entities, because what a hub serves is not block state in the way a network
     * connection is. They are still recorded here because the block behind the model needs to know which
     * sides are taken, and an arm drawn towards a port is hidden inside it anyway.
     *
     * Entity-backed rather than state-backed because note block backing states occlude: a model this
     * thin over one would cull the touching face of an opaque neighbour and leave a hole in it. The
     * barrier behind an entity-backed block does not occlude. The price is that collision stays a full
     * cube — it comes from the backing state, and no inert vanilla state is shaped like a core with
     * arms. Nova's own Machines addon makes the same trade for its solar panel.
     */
    private fun hub(name: String, models: String, constructor: TileEntityConstructor): NovaTileEntityBlock =
        tileEntity(name, constructor) {
            behaviors(TileEntityLimited, TileEntityDrops, TileEntityInteractive, DEVICE, BlockSounds(SoundGroup.METAL))
            stateProperties(
                ScopedBlockStateProperties.NORTH,
                ScopedBlockStateProperties.EAST,
                ScopedBlockStateProperties.SOUTH,
                ScopedBlockStateProperties.WEST,
                ScopedBlockStateProperties.UP,
                ScopedBlockStateProperties.DOWN,
                ScopedBlockStateProperties.POWERED
            )
            entityBacked({
                // Same two backings the cable uses, and for the same reason. A chain gives a hub that
                // runs straight through a collider along the run; anything else falls back to the
                // structure void, whose shape is a small cube in the middle. Both are real blocks, so a
                // hub is mined and right-clicked like any other, with its hardness and its tool.
                //
                // A chain spans its whole block, so it is only laid where *both* ends of the axis are
                // taken — otherwise its collider would jut out into the empty side. Taken means an arm
                // or a port: a port reaches the block edge just as an arm does, so a connector between
                // two chests earns its chain the same way one in the middle of a cable run does.
                val north = getPropertyValueOrThrow(BlockStateProperties.NORTH)
                val east = getPropertyValueOrThrow(BlockStateProperties.EAST)
                val south = getPropertyValueOrThrow(BlockStateProperties.SOUTH)
                val west = getPropertyValueOrThrow(BlockStateProperties.WEST)
                val up = getPropertyValueOrThrow(BlockStateProperties.UP)
                val down = getPropertyValueOrThrow(BlockStateProperties.DOWN)

                when {
                    east && west -> MojangBlocks.IRON_CHAIN.defaultBlockState()
                        .setValue(MojangBlockStateProperties.AXIS, Axis.X)

                    north && south -> MojangBlocks.IRON_CHAIN.defaultBlockState()
                        .setValue(MojangBlockStateProperties.AXIS, Axis.Z)

                    up && down -> MojangBlocks.IRON_CHAIN.defaultBlockState()
                        .setValue(MojangBlockStateProperties.AXIS, Axis.Y)

                    else -> MojangBlocks.STRUCTURE_VOID.defaultBlockState()
                }
            }, {
                val id = encodeFlags(
                    getPropertyValueOrThrow(BlockStateProperties.NORTH),
                    getPropertyValueOrThrow(BlockStateProperties.EAST),
                    getPropertyValueOrThrow(BlockStateProperties.SOUTH),
                    getPropertyValueOrThrow(BlockStateProperties.WEST),
                    getPropertyValueOrThrow(BlockStateProperties.UP),
                    getPropertyValueOrThrow(BlockStateProperties.DOWN)
                )

                // a whole second set of 64, dark. The arms are the same shape either way; only the core
                // texture differs, and a model cannot pick a texture at runtime
                val lit = if (getPropertyValueOrThrow(BlockStateProperties.POWERED)) "" else "off/"
                getModel("block/$models/$lit$id")
            })
        }

}
