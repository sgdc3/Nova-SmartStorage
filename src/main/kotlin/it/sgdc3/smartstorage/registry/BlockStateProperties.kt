package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage
import xyz.xenondevs.nova.util.Key
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty

/**
 * Block state properties this addon adds.
 *
 * The six cartesian ones are the connections of [it.sgdc3.smartstorage.tileentity.StorageCable] and the
 * occupied sides of a hub.
 */
object BlockStateProperties {

    val NORTH = BooleanProperty(Key(SmartStorage, "north"))
    val EAST = BooleanProperty(Key(SmartStorage, "east"))
    val SOUTH = BooleanProperty(Key(SmartStorage, "south"))
    val WEST = BooleanProperty(Key(SmartStorage, "west"))
    val UP = BooleanProperty(Key(SmartStorage, "up"))
    val DOWN = BooleanProperty(Key(SmartStorage, "down"))

    /**
     * Whether a [it.sgdc3.smartstorage.tileentity.StorageBarrel] is locked onto its item.
     *
     * Block state rather than tile entity data alone, because it is the one thing about a barrel that
     * has to show on the *block* — a padlock stamped on its front — and a texture can only follow block
     * state. The tile entity remains the authority and pushes this out when the switch is flipped.
     */
    val LOCKED = BooleanProperty(Key(SmartStorage, "locked"))

    /**
     * Whether a device is being kept running by a controller.
     *
     * Block state rather than tile entity data alone, because it decides which *texture* the block wears
     * — the lit one that blinks, or the dark one that does not — and a texture can only follow block
     * state. Every device drives it from its own tick.
     */
    val POWERED = BooleanProperty(Key(SmartStorage, "powered"))

}

object ScopedBlockStateProperties {

    val NORTH = BlockStateProperties.NORTH.scope { false }
    val EAST = BlockStateProperties.EAST.scope { false }
    val SOUTH = BlockStateProperties.SOUTH.scope { false }
    val WEST = BlockStateProperties.WEST.scope { false }
    val UP = BlockStateProperties.UP.scope { false }
    val DOWN = BlockStateProperties.DOWN.scope { false }

    val LOCKED = BlockStateProperties.LOCKED.scope { false }

    /**
     * Off by default, so a block that has just been placed — or one whose saved state predates this
     * property — is dark until its first tick says otherwise. Lighting up late is honest; claiming to be
     * powered before anyone has checked is not.
     */
    val POWERED = BlockStateProperties.POWERED.scope { false }

}
