package it.sgdc3.smartstorage.tileentity

import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.world.format.NetworkState
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The item network inventories of everything pressed directly against a block.
 *
 * Nova joins two end points that *touch* into one network with no cable between them. That is what makes
 * a Storage Interface against a chest work at all, and it is the wrong default for storage that is meant
 * to sit still: a barrel and a chest side by side are each willing to give and to take, so the
 * distributor does the obvious thing and shuttles items across, every tick, forever. Neither block asked
 * for it and neither has a side config to be told to stop.
 *
 * A barrel is passive. It moves items when a pipe, a connector or a player asks it to, and at no other
 * time — so it declines every partner it is merely touching and keeps the ones it can only have been
 * wired to.
 *
 * This is the rule the wall already ran on, widened. `canExchangeItemsWith` refused another barrel and
 * the controller that speaks for it, which covered the two cases anyone had noticed; a chest on the next
 * block is the same pathology with a different block on the other side of it.
 *
 * ## Why a set of inventories rather than closed faces
 *
 * The other way to say this is to set the touching face's connection type to NONE, and it is the worse
 * one for two separate reasons.
 *
 * A face Nova still holds a connection to whose type is NONE makes its network builder throw, and that
 * exception aborts the build for every network of every type — a bug this addon has already paid for
 * once. And closing the face cuts the very notifications needed to open it again, so the block would
 * have to poll to find out the chest had been broken.
 *
 * Refusing the *pair* leaves the topology exactly as Nova built it. The connection still exists, the
 * updates still arrive, and the only thing declined is the transfer.
 */
internal class TouchingInventories {

    @Volatile
    private var inventories: Set<NetworkedInventory> = emptySet()

    operator fun contains(inventory: NetworkedInventory): Boolean = inventory in inventories

    /**
     * Identity throughout, not equality: two inventories that compare equal are still two different
     * pieces of storage, and the question here is only which object is on the other side.
     */
    fun refresh(state: NetworkState, pos: BlockPos) {
        val found = Collections.newSetFromMap(IdentityHashMap<NetworkedInventory, Boolean>())

        for (node in state.getNearbyNodes(pos, CUBE_FACES).values) {
            if (node !is NetworkEndPoint)
                continue

            for (holder in node.holders) {
                if (holder !is ItemHolder)
                    continue

                found += holder.containers.keys
                // A holder carrying more than one container is addressed through a single merged view,
                // and that view is what the distributor would hand over — so refusing the parts alone
                // would refuse nothing. Holders with one container hand back that container here, which
                // is already in the set.
                found += holder.mergedInventory
            }
        }

        inventories = found
    }

}
