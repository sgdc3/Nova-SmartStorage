package it.sgdc3.smartstorage.tileentity

import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes.ITEM
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
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
/**
 * Sets what each item face of this end point will do, and answers whether anything moved.
 *
 * A face with passive storage against it is closed outright; the rest stay open. With
 * [extractOnlyFromBelow], every face but the bottom one is narrowed to insertion.
 *
 * ## Why the bottom face is special
 *
 * Nova's item network has no notion of which way a hopper points. It sees two end points touching and
 * lets the distributor decide the direction, so an open face is open both ways: a hopper set beside a
 * barrel — which in vanilla would not reach it at all — could drain it, and one pointing *into* it could
 * take back what it had just put in. Neither is what anybody built.
 *
 * Giving only the bottom face both directions restores the rule players already expect from a hopper:
 * items go in from the sides and the top and come out underneath. The cost is that a cable on a side can
 * only fill a barrel — to drain one, put the cable below it.
 *
 * The controller does not take this. A wall's mouth is meant to be served by "one pipe or one connector"
 * on whichever side is convenient, and sides that only accept would break that.
 *
 * ## Why the connection config, and not [NetworkedInventory.canExchangeItemsWith]
 *
 * Refusing the *pair* was the obvious way to say this and it does not work, for a reason worth writing
 * down. Nova wraps every inventory in a `FilteredNetworkedInventory` and its `canExchangeItemsWith` is
 * one line — `this.inventory.canExchangeItemsWith(other.inventory)` — which the distributor calls on
 * **one side of the pair only**. When a chest gives and a barrel takes, the side asked is the chest;
 * it is Nova's own and it answers yes. The barrel's refusal is never consulted at all.
 *
 * That hook can therefore only decline partners when *we* happen to be the side asked, which is why a
 * barrel refusing another barrel worked and a barrel refusing a chest did nothing. The connection config
 * is a statement about our own face, so nobody else has to agree with it.
 *
 * ## The two costs
 *
 * A face set to NONE that Nova still holds a connection to makes its network builder throw, and that
 * takes down every network of every type. Hence `handleEndPointAllowedFacesChange` on every change, the
 * same protocol Nova's own side config menu uses.
 *
 * And a closed face stops the updates that would say the chest is gone, so whoever calls this has to do
 * it on a timer rather than only when notified.
 */
/**
 * Whether [node] is a neighbour that only ever *holds* items, as opposed to one that moves them.
 *
 * This is the whole distinction the rule turns on. A chest pressed against a barrel is two pieces of
 * storage that will each give and each take, so the distributor shuttles a stack between them forever
 * and neither block was placed asking for it. A hopper is not that: it exists to move items, and a
 * player who sets one against a barrel has said exactly what they want to happen. So has one who runs a
 * cable to it, or bolts a machine onto it.
 *
 * Nova draws the line for us — a chest and a plain container are their own classes, and so, separately,
 * are a hopper, a furnace, a crafter and a brewing stand — and this addon's own barrels are the other
 * half of it.
 *
 * Listing the passive kinds rather than the active ones is deliberate, because the two ways of being
 * wrong are not equal: miss a passive block and it keeps shuttling, which is the bug we started with;
 * miss an active one and it stops working, which is somebody's build breaking for no visible reason.
 *
 * By name, and not for want of trying: Nova's vanilla tile entities are `internal`, so they are public
 * in the bytecode and unreferenceable from Kotlin. The alternative was reading the neighbour's block
 * material through Bukkit, which is a main-thread question and this runs on the network's.
 *
 * The cost is real and worth stating plainly: a rename upstream turns this off silently, and the
 * symptom would be a barrel quietly filling itself from the chest beside it again. A Nova upgrade is
 * the moment to check these two names still exist.
 */
private fun isPassiveStorage(node: NetworkNode?): Boolean = when {
    node == null -> false
    node is StorageBarrel || node is BarrelController -> true
    else -> node.javaClass.simpleName in PASSIVE_VANILLA_STORAGE
}

private val PASSIVE_VANILLA_STORAGE = setOf("VanillaChestTileEntity", "VanillaContainerTileEntity")

internal suspend fun NetworkEndPoint.restrictItemFaces(
    state: NetworkState,
    pos: BlockPos,
    holder: ItemHolder,
    extractOnlyFromBelow: Boolean
): Boolean {
    var changed = false
    val nearby = state.getNearbyNodes(pos, CUBE_FACES)

    for (face in CUBE_FACES) {
        val wanted = when {
            isPassiveStorage(nearby[face]) -> NetworkConnectionType.NONE
            !extractOnlyFromBelow || face == BlockFace.DOWN -> NetworkConnectionType.BUFFER
            else -> NetworkConnectionType.INSERT
        }

        if (holder.connectionConfig[face] == wanted)
            continue

        holder.connectionConfig[face] = wanted
        state.getNetwork(this, ITEM, face)?.markDirty()
        state.handleEndPointAllowedFacesChange(this, ITEM, face)
        changed = true
    }

    return changed
}

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
            // the same line the faces are drawn on: a hopper or a machine against this block is a build
            // somebody made on purpose, and refusing it would break it
            if (node !is NetworkEndPoint || !isPassiveStorage(node))
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
