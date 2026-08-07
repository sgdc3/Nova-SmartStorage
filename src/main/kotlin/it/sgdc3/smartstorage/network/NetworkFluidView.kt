package it.sgdc3.smartstorage.network

import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.container.NetworkedFluidContainer
import java.util.UUID

/**
 * Presents one fluid of a storage network to Nova's fluid network as if it were a tank.
 *
 * One view per fluid, because a [NetworkedFluidContainer] holds one type at a time and a storage network
 * does not. The water view reports the network's water, the lava view its lava, and either will take
 * whatever it is handed — so a face configured to one of them is really a choice about what comes *out*
 * of that side.
 *
 * ## Why [allowedTypes] is every type rather than just [fluid]
 *
 * Nova skips a whole channel if any container on it disallows the fluid being moved
 * (`FluidDistributor.findFluidType`). A view that narrowed its allowed types to water would therefore
 * stop lava moving through every pipe it touched — an interface breaking somebody else's plumbing by
 * being attached to it. Reporting the network's water while accepting anything is the honest position:
 * the network really will take either.
 *
 * The remaining sharp edge is Nova's, not ours, and it is the same one two mismatched tanks have: put
 * both views on one channel while the network holds *both* fluids and that channel stalls, because the
 * distributor refuses to move a mix. Give them separate faces on separate pipes.
 *
 * ## Why [amount] is not simply the network's amount
 *
 * Nova's fluid distributor sizes a transfer from `providers.sumOf { it.amount }`, hands the whole of it
 * to the consumers, and only *then* asks the providers to produce it — throwing if they cannot. Nova's
 * own containers always can, because each owns its fluid and `amount` is a field. Ours do not: every
 * view over one storage network reports the same aggregate, so two interfaces on one system promise it
 * twice and the difference is handed out for free before the throw ever happens.
 *
 * So the promise is divided by the number of gateways on the system — see
 * [StorageNetwork.promisableAmountOf]. One interface is unaffected; two each offer half, which adds up
 * to exactly what is there however the distributor groups them.
 *
 * The remaining gap is genuinely a race: the aggregate can shrink between the give and the take if a
 * player empties a cell from a terminal in between. On Paper today that gap does not exist, because Nova
 * drives network ticks from a synchronous task that blocks the server thread. That is a property of the
 * ticker rather than a promise, which is why it is written down rather than relied on silently.
 */
class NetworkFluidView internal constructor(
    private val owner: StorageEndPoint,
    override val uuid: UUID,
    /**
     * The one fluid this view reports. Public because a face's extract filter is checked against this
     * fluid's bucket — see [it.sgdc3.smartstorage.tileentity.StorageInterface].
     */
    val fluid: FluidType,
    /**
     * How much fluid may still come in this tick, and how much may still go out — see [TransferBudget],
     * which is also where the reason these are two rather than one is written down.
     */
    private val input: TransferBudget,
    private val output: TransferBudget
) : NetworkedFluidContainer {

    override val allowedTypes: Set<FluidType> = FluidType.entries.toSet()

    private val network: StorageNetwork?
        get() = owner.storageNetwork?.takeIf { it.isOnline }

    /**
     * This view's share of what the system will hand over, not what the system holds — see the class
     * KDoc for why it is a share and [StorageProvider.extractableCountOf] for why it is what will be
     * handed over.
     */
    override val amount: Long
        get() = output.allow(network?.promisableAmountOf(fluid) ?: 0L)

    /**
     * This view's fluid, or null while the network holds none of it — which is what makes an empty
     * network read as an empty tank rather than as a tank of nothing.
     */
    override val type: FluidType?
        get() = if (amount > 0L) fluid else null

    /**
     * Walks every cell and tank on the network, so it is deliberately not what [isFull] and [accepts]
     * are answered with — only what a menu asks for when it wants a figure to print.
     */
    override val capacity: Long
        get() = network?.fluidCapacity() ?: 0L

    override fun addFluid(type: FluidType, amount: Long): Long {
        val network = network ?: return 0L

        val allowed = input.allow(amount)
        if (allowed <= 0L)
            return 0L

        // both this and StorageNetwork.insertFluid report what was left over, so the difference is what
        // went in
        val added = allowed - network.insertFluid(type, allowed)
        input.spend(added)

        return added
    }

    override fun takeFluid(amount: Long): Long {
        val allowed = output.allow(amount)
        if (allowed <= 0L)
            return 0L

        val taken = network?.extractFluid(fluid, allowed) ?: 0L
        output.spend(taken)

        return taken
    }

    /**
     * Full once the incoming budget is spent, because for the rest of this tick nothing more can arrive.
     */
    override fun isFull(): Boolean =
        input.available() <= 0L || network?.hasFreeFluidSpace() != true

    override fun isEmpty(): Boolean =
        amount <= 0L

    /**
     * Answered from "is there room at all" rather than from [capacity], which would walk the whole
     * network to answer a question asked once per consumer per transfer. Over-promising costs nothing:
     * [addFluid] reports what actually went in, and the distributor believes that rather than this.
     */
    override fun accepts(type: FluidType, amount: Long): Boolean =
        amount >= 0L && input.available() > 0L && network?.hasFreeFluidSpace() == true

    override fun accepts(type: FluidType): Boolean =
        input.available() > 0L && network?.hasFreeFluidSpace() == true

}
