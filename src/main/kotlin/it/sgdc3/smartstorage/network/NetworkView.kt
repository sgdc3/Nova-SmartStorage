package it.sgdc3.smartstorage.network

import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.util.RateLimitedError
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory
import java.util.UUID

/**
 * Shared by every view: one network handing out what it did not have would otherwise be one log line per
 * transfer per tick, from every interface at once.
 */
private val SHORTFALL = RateLimitedError()

/**
 * Presents an entire storage network to Nova's item network as if it were a plain inventory.
 *
 * This is what makes hoppers, chests, Logistics cables and Nova machines work with a storage network
 * without a single line of integration code on either side.
 *
 * Deliberately unfiltered. Filtering belongs to the *face* an item is passing through, not to the
 * storage behind it, and Nova already applies a holder's per-face filters in its own distributor —
 * doing it a second time here would be a second rule to keep in step with the first. See
 * [it.sgdc3.smartstorage.tileentity.StorageInterface] for the one that matters, which is that a face
 * hands nothing out until it has been told what may go.
 *
 * The contract Nova's item network relies on (see `ItemNetworkGroup.preTick` and `ItemDistributor`):
 * [size] is read once when the network group is built and must never change, [copyContents] is called
 * once per network tick, and [canTake]/[take] are then called against that snapshot's slot indices.
 * So [copyContents] records the slot ordering, and [take] resolves a slot back to an item *type* and
 * extracts by type — which stays correct even if the network changed in between.
 */
class NetworkView internal constructor(
    private val owner: StorageEndPoint,
    override val uuid: UUID,
    override val size: Int,
    /**
     * How much may still come *in* this tick, and how much may still go out. Two, not one — see
     * [TransferBudget] for why a shared pool would let one direction spend what the other promised.
     */
    private val input: TransferBudget,
    private val output: TransferBudget,
    /**
     * Whether some side that is currently handing items out would let this type past its filter.
     *
     * Only ever consulted to decide *what to offer*, never whether to hand anything over — Nova applies
     * the real filters itself, per face, and doing it twice would be two rules to keep in step. See
     * [copyContents] for why the offer has to know.
     */
    private val wantedOut: (ItemType) -> Boolean = { true }
) : NetworkedInventory {

    /**
     * The item type each snapshot slot referred to, written by [copyContents].
     */
    @Volatile
    private var slotTypes: Array<ItemType?> = arrayOfNulls(size)

    private val network: StorageNetwork?
        get() = owner.storageNetwork?.takeIf { it.isOnline }

    override fun add(itemStack: ItemStack, amount: Int): Int {
        val network = network ?: return amount
        val type = ItemType.of(itemStack) ?: return amount

        // what the budget will not let through is not refused, it is simply left over — which is the
        // same answer a full network gives, and the caller already knows what to do with it
        val allowed = input.allow(amount.toLong())
        if (allowed <= 0L)
            return amount

        // both this method and StorageNetwork.insert report the leftover
        val leftOver = network.insert(type, allowed)
        input.spend(allowed - leftOver)

        return (amount - allowed + leftOver).toInt()
    }

    /**
     * Asked of what the system will actually *give*, not of what it holds.
     *
     * The distributor adds to the destination before it calls [take], and [take] has no way of reporting
     * that it came up short — so a `true` here is a promise, and storage the network can see but not
     * reach (a connector port with extraction switched off, most of all) must not be counted towards it.
     * Answering with [StorageNetwork.countOf] made every such container an item generator.
     */
    override fun canTake(slot: Int, amount: Int): Boolean {
        val type = slotTypes.getOrNull(slot) ?: return false
        val network = network ?: return false

        // the budget is part of the promise: saying yes and then handing over less because the tick ran
        // out is the same lie as saying yes about items that are not there
        if (amount > output.available())
            return false

        return network.extractableCountOf(type) >= amount
    }

    /**
     * The other half of the promise [canTake] made, and there is no way to report that it went unkept:
     * Nova's distributor has already handed the items to the destination by the time this runs, and the
     * signature returns nothing. A short take is therefore not an error this can recover from — it is
     * items created out of nothing.
     *
     * Two structural facts keep the promise good, and both are somebody else's to break:
     *
     * 1. Nova ticks networks from `runBlocking` on the main thread, so no menu click, deposit slot or
     *    tile entity tick can land between the two calls.
     * 2. `parallel_ticking` runs *clusters* in parallel, and a cluster is the transitive closure over
     *    networks sharing a node — so every item network reaching this storage system is in the same
     *    cluster as the storage network itself, and they tick in sequence.
     *
     * Neither is a contract, and the Folia ticker sitting commented out in Nova's `NetworkTicker` would
     * end the first one. So the shortfall is checked rather than assumed: if this ever fires, items are
     * being duplicated and the cause is above, not here.
     */
    override fun take(slot: Int, amount: Int) {
        val type = slotTypes.getOrNull(slot) ?: return
        val taken = network?.extract(type, amount.toLong()) ?: 0L
        output.spend(taken)

        if (taken < amount) {
            SHORTFALL.log {
                "Storage network handed out $amount× $type but only had $taken: " +
                    "${amount - taken} item(s) were created. This means canTake promised what extract " +
                    "could not deliver — see NetworkView.take."
            }
        }
    }

    /**
     * A budget that has run out reads as full, and as empty, because for the rest of this tick that is
     * exactly what this side is: nothing more goes in and nothing more comes out. Saying so lets the
     * distributor drop this inventory instead of asking it slot by slot.
     */
    override fun isFull(): Boolean =
        input.available() <= 0L || network?.hasFreeSpace() != true

    override fun isEmpty(): Boolean =
        output.available() <= 0L || network?.isEmpty() != false

    override fun copyContents(destination: Array<ItemStack>) {
        val network = network
        val types = arrayOfNulls<ItemType>(size)

        if (network != null) {
            // a stable order matters: the distributor takes this snapshot once per tick and then
            // addresses slots by index for the rest of it
            // the extractable view, for the same reason canTake uses it: a slot in this snapshot is an
            // offer, and offering something the network will refuse to hand over is how items get made
            // Ordered by what the sides have asked for first, and only then by how much there is.
            //
            // There are [size] slots and a storage system holds far more types than that, so this is a
            // window onto it — and Nova applies a face's extract filter to the window *after* it is
            // taken. A window chosen by sheer quantity therefore silently defeats every whitelist for
            // something that is not among the most numerous: the item never appears, the filter has
            // nothing to pass, and the side moves nothing while a blacklist on the same side works
            // perfectly. Asking what the filters want turns that around — a side told exactly what may
            // go is a side asking for something specific, and it is the one thing that must be in the
            // window.
            //
            // Quantity still decides the rest, which is what bulk export wants, and a blacklist is
            // unaffected: it wants nearly everything, so nearly everything ranks alike and the order is
            // the one it always was.
            val entries = network.extractableSnapshot().entries
                .map { wantedOut(it.key) to it }
                .sortedWith(
                    compareByDescending<Pair<Boolean, Map.Entry<ItemType, Long>>> { it.first }
                        .thenByDescending { it.second.value }
                        .thenBy { it.second.key.stack.type.name }
                )
                .map { it.second }

            // No slot offers more than this tick's allowance, and that is not a nicety — it is the only
            // place the rate can be applied on the way out.
            //
            // [canTake] is a promise and the distributor asks it all or nothing: it works out one figure
            // — the smaller of what the slot shows and what its cables will carry — and either takes
            // exactly that or moves on. A slot showing a full stack against a budget of eight is
            // therefore a slot asked for sixty-four and refused, every tick, forever. Worst where it
            // matters most: an interface bolted straight onto a chest has no cable for Nova to take a
            // rate from, so the figure it asks for is simply the stack.
            //
            // Insertion never had this because [add] answers with the leftover, so a budget smaller than
            // the offer is a partial transfer rather than no transfer. Out here, the offer has to be the
            // small number.
            val budget = output.available()

            for (i in 0..<size) {
                val entry = entries.getOrNull(i)
                if (entry == null) {
                    destination[i] = ItemStack.empty()
                    continue
                }

                val offered = minOf(entry.value, entry.key.maxStackSize.toLong(), budget)
                if (offered <= 0L) {
                    destination[i] = ItemStack.empty()
                    continue
                }

                types[i] = entry.key
                destination[i] = entry.key.createStack(offered.toInt())
            }
        } else {
            for (i in 0..<size)
                destination[i] = ItemStack.empty()
        }

        slotTypes = types
    }

    /**
     * Two interfaces on the same storage system would otherwise shuttle items back and forth forever.
     *
     * Compared by cluster, not by network: two interfaces on opposite sides of a device that splits a
     * cable run are on different [StorageNetwork]s but the same storage, and moving items between them
     * would be moving items to themselves.
     */
    override fun canExchangeItemsWith(other: NetworkedInventory): Boolean {
        if (other === this)
            return false

        if (other is NetworkView) {
            val group = owner.storageNetwork?.group
            return group == null || group !== other.owner.storageNetwork?.group
        }

        return true
    }

}
