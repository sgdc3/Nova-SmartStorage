package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.registry.BlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity

/**
 * Lights a device's face, or puts it out.
 *
 * Shared because every device does the same thing with it and a device that got it wrong would be
 * telling the player something false about their network — a blinking machine that is not running is
 * worse than no indicator at all.
 *
 * Compares before writing, because this is called from a tick: a block state update is a packet to
 * everyone who can see the block, and almost every tick has nothing to say.
 *
 * @return whether anything changed. Callers use it to redraw the same fact inside the menu, which is the
 * other place a device says whether it is running — one signal, so the face and the lamp in the menu
 * cannot disagree, and neither is rebuilt on the ticks where nothing happened.
 */
internal fun TileEntity.setPowered(powered: Boolean): Boolean {
    if (blockState[BlockStateProperties.POWERED] == powered)
        return false

    updateBlockState(blockState.with(BlockStateProperties.POWERED, powered))
    return true
}
