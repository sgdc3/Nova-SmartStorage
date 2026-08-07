package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.priorityIcon
import it.sgdc3.smartstorage.registry.GuiItems
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.Models
import it.sgdc3.smartstorage.registry.NetworkTypes
import it.sgdc3.smartstorage.registry.TERMINAL_REFRESH_TICKS
import it.sgdc3.smartstorage.network.DEFAULT_PRIORITY
import it.sgdc3.smartstorage.network.FluidProvider
import it.sgdc3.smartstorage.network.PRIORITY_RANGE
import it.sgdc3.smartstorage.network.StorageEndPoint
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.network.StorageProvider
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.util.getItemFilter
import it.sgdc3.smartstorage.util.isItemFilter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.enumMap
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.inventory.VirtualInventory
import xyz.xenondevs.invui.inventory.event.ItemPreUpdateEvent
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.window.Window
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.ui.menu.addIngredient
import xyz.xenondevs.nova.ui.menu.item.AddNumberItem
import xyz.xenondevs.nova.ui.menu.item.RemoveNumberItem
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.container.NetworkedFluidContainer
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.holder.FluidHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemFilter
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.item.DefaultGuiItems
import xyz.xenondevs.nova.world.item.NovaItem
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Which materials [StorageConnector.resolveBacking] has already found out about: true for the ones whose
 * block state is a [Container], false for the ones it is not.
 *
 * Shared by every connector on the server, because the answer is about the material and nothing else. A
 * concurrent map rather than a plain one not because resolution runs off the main thread — it does not,
 * it needs Bukkit — but because a cache is a poor place to depend on that staying true.
 */
private val CONTAINER_MATERIALS = ConcurrentHashMap<Material, Boolean>()

/**
 * Turns every container it touches — chests, barrels, shulker boxes, hoppers — into storage for the
 * network, exactly as if they were drives full of cells.
 *
 * It is a hub, not a plug: all six sides are live at once, so one connector wedged between two chests
 * serves both, and a row of barrels needs one connector rather than one per barrel. Each of those sides
 * is a [ContainerPort] with its own filter and its own priority, because a chest kept for overflow and a
 * barrel kept for iron are two different pieces of storage that happen to share a connector. Right-click
 * a port to configure that side; right-click the core for the summary.
 *
 * Which thread does what is deliberate, and the kinds of backing differ.
 *
 * A vanilla container can only be *found* through Bukkit, so [handleTick] — which runs on the server
 * thread, since it also drives display entities and hitboxes — resolves the reference there and the
 * network ticks then use it from their own thread. That mirrors how Nova exposes vanilla containers to
 * its item network: the reference points at the container's live backing array, so reads and writes are
 * plain array access, the window for a torn read is one tick, and the worst case is a transfer that
 * reports slightly stale contents.
 *
 * A Nova end point needs none of that. It is found in Nova's own chunk data without touching a single
 * Bukkit API, and its inventory is a [NetworkedInventory] — the interface Nova's item network drives
 * from its own ticker — so both halves, discovery and use, stay clear of the server thread's APIs.
 *
 * This addon's own [StorageBarrel] and [BarrelController] are found the same way, but are then talked
 * to directly rather than through the item network's view of them. That view is one slot per barrel,
 * which is the right thing for a pipe and the wrong thing here: a barrel holds thousands of one item,
 * and a terminal that listed 64 of them would be lying about the whole point of the block.
 *
 * **A [NetworkedBacking] is a weaker guarantee, and it is worth stating plainly.** Reaching into another
 * addon's inventory means reaching into state that its own item network also mutates, from its own group
 * tick — a different group from ours, so nothing serialises the two. [StorageLock] cannot help: it is
 * ours, and the other side has never heard of it. Logistics' storage unit, for one, updates its count
 * with a plain `amount += transferred`, so an unlucky interleaving loses or duplicates items.
 *
 * This is accepted rather than solved. The alternatives are to refuse any backing that is not already on
 * our own network — which would reject the ordinary case of a storage unit simply standing next to a
 * connector, i.e. the whole feature — or to give the connector its own `ItemHolder`, which changes what
 * the block *is* to every item cable in the world. Neither is worth it for a race that needs two network
 * groups to touch the same inventory in the same tick.
 *
 * Every port starts at the same priority as every other piece of storage on the network, so nothing is
 * ordered until a player says it should be: raise a side to fill it first and drain it last, lower one
 * to make it the overflow. Shipping chests below drive bays by default would be guessing at a layout,
 * and the guess is wrong for anyone whose barrels are the point.
 */
class StorageConnector(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : StorageHub(pos, state, data), StorageEndPoint {

    override val portModel = Models.CONNECTOR_ATTACHMENT
    override val portModelOff = Models.CONNECTOR_ATTACHMENT_OFF

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    /**
     * One per side, whether or not anything is mounted there, so a side keeps its filter and its
     * priority while the chest is temporarily gone.
     */
    private val ports: Map<BlockFace, ContainerPort> =
        CUBE_FACES.associateWithTo(enumMap()) { ContainerPort(it) }

    /**
     * Only the sides that currently have a container. Published as a whole, so the network threads
     * always read one coherent list rather than a half-updated one.
     */
    @Volatile
    override var storageProviders: List<StorageProvider> = emptyList()
        private set

    /**
     * Only the sides that currently have a tank, published the same way and for the same reason. A side
     * is never in both lists: a neighbour is a container or a tank, not both.
     */
    @Volatile
    override var fluidProviders: List<FluidProvider> = emptyList()
        private set

    init {
        holders += storageHolder
    }

    override fun handleEnable() {
        super.handleEnable()
        refreshContainers()
    }

    override fun handleTick() {
        // cheap enough to redo every tick, and it means a container that was broken, replaced or turned
        // into a double chest is picked up within 50 ms
        refreshContainers()
        setPowered(storageNetwork?.isOnline == true)

        // Each side's icon prints how full the container on it is, and that changes without the set of
        // ports changing — which is all refreshContainers notices. Gated on somebody looking and on the
        // same interval as every other readout, because rebuilding seven icons per viewer per tick for
        // a number nobody is reading is exactly the work the guard in refreshContainers exists to avoid.
        if (serverTick % max(1, TERMINAL_REFRESH_TICKS) == 0 && menuContainer.getMenus<StorageConnectorMenu>().any())
            menuContainer.forEachMenu(StorageConnectorMenu::update)
    }

    override fun handleDisable() {
        clearPorts()
        storageNetwork = null
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        clearPorts()
        storageNetwork = null
        super.handleBreak(ctx)
    }

    private fun clearPorts() {
        storageProviders = emptyList()
        fluidProviders = emptyList()
        for (port in ports.values) {
            port.backing = null
            port.fluidBacking = null
        }
    }

    private fun refreshContainers() {
        val active = HashSet<BlockFace>()
        for (face in CUBE_FACES) {
            val port = ports.getValue(face)

            resolvePort(face, port)
            if (port.isActive)
                active += face
        }

        val mounted = active.map(ports::getValue)
        storageProviders = mounted.filter { it.backing != null }
        fluidProviders = mounted.filter { it.fluidBacking != null }

        if (setPortFaces(active))
            menuContainer.forEachMenu(StorageConnectorMenu::update)
    }

    /**
     * Finds what is on one side, of either kind, in a single pass.
     *
     * One pass rather than two because the expensive parts — the chunk check and the tile entity lookup
     * — are the same question for both, and this runs six times per connector per tick.
     */
    private fun resolvePort(face: BlockFace, port: ContainerPort) {
        val neighbour = pos.advance(face)

        // Nova's own lookup throws for a chunk it has not loaded, and a connector on a chunk border has
        // neighbours in the next one. Asking Bukkit whether the chunk is loaded neither loads it nor
        // builds anything — unlike reading a block, which loads the chunk to answer.
        if (!pos.world.isChunkLoaded(neighbour.x shr 4, neighbour.z shr 4)) {
            port.backing = null
            port.fluidBacking = null
            return
        }

        val tileEntity = WorldDataManager.getTileEntity(neighbour)
        port.backing = resolveBacking(neighbour, tileEntity)
        port.fluidBacking = resolveFluidBacking(tileEntity)
    }

    /**
     * A tank, of any kind Nova recognises as one.
     *
     * There is no vanilla half to this, which is why it is so much shorter than [resolveBacking]: a
     * cauldron is not a tank in any sense the network could use — three levels of water, no type it will
     * report, and no way to put lava in it — so a fluid side is a Nova end point or it is nothing.
     */
    private fun resolveFluidBacking(tileEntity: TileEntity?): FluidBacking? {
        if (tileEntity == null || tileEntity is StorageEndPoint || tileEntity !is NetworkEndPoint)
            return null

        val holder = tileEntity.holders.filterIsInstance<FluidHolder>().firstOrNull() ?: return null
        val container = holder.containers
            .entries.firstOrNull { (_, type) -> type == NetworkConnectionType.BUFFER }
            ?.key
            ?: return null

        return FluidBacking(container)
    }

    /**
     * A vanilla container has to be found through its block state, which is why this only ever runs on
     * the server thread. A Nova end point does not: its inventories implement [NetworkedInventory],
     * which is the interface Nova's own item network drives from an async ticker, so the reference can
     * simply be held and used from ours.
     *
     * Only containers the owner exposes as a full buffer are taken. That is the difference between
     * storage and a working slot: a Logistics storage unit registers its inventory as `BUFFER`, while a
     * machine's input is `INSERT` and its output `EXTRACT`, and pulling a furnace's fuel back out from
     * under it is not what anyone means by attaching storage.
     */
    private fun resolveBacking(neighbour: BlockPos, tileEntity: TileEntity?): Backing? {
        // Nova first, deliberately. A Nova end point is a lookup in Nova's own chunk data and touches no
        // Bukkit API at all; going through the block state to discover it would build a CraftBlockState
        // for a question Nova can answer directly. Nothing is backed by a container block, so a Nova
        // hit can never be a vanilla container too.
        if (tileEntity != null) {
            // one of ours would mean the network swallowing itself
            if (tileEntity is StorageEndPoint)
                return null

            // Our own barrels come first and are read directly: they are the one kind of storage whose
            // slots are not the whole truth about it. A barrel a controller has claimed resolves to the
            // controller, so that reaching the wall through one of its barrels and reaching it through
            // the block that speaks for it are recognised as the same storage rather than counted twice.
            when (tileEntity) {
                is StorageBarrel ->
                    return tileEntity.controller?.let(::BarrelWallBacking) ?: BarrelBacking(tileEntity)

                is BarrelController -> return BarrelWallBacking(tileEntity)
            }

            if (tileEntity !is NetworkEndPoint)
                return null

            val holder = tileEntity.holders.filterIsInstance<ItemHolder>().firstOrNull() ?: return null
            val inventory = holder.containers
                .entries.firstOrNull { (_, type) -> type == NetworkConnectionType.BUFFER }
                ?.key
                ?: return null

            return NetworkedBacking(inventory)
        }

        // Only a vanilla container needs the block state. Most sides of most connectors are air, and
        // reading the material first is a palette lookup where getState allocates.
        val block = neighbour.block
        val material = block.type
        if (material.isAir)
            return null

        // ...and most of the rest is stone. Whether a material *can* be a container is a property of the
        // block type rather than of the block, so it is worth learning once and never asking again —
        // without this, a walled-in connector built six CraftBlockStates a tick, forever, to be told six
        // times a tick that stone is not a chest. Container materials are still resolved every tick,
        // which is what catches a chest becoming a double chest.
        val known = CONTAINER_MATERIALS[material]
        if (known == false)
            return null

        // getState(false) skips the snapshot copy and hands back the live block state
        val container = block.getState(false) as? Container
        if (known == null)
            CONTAINER_MATERIALS[material] = container != null

        return container?.let { VanillaBacking(it.inventory) }
    }

    override fun openPortMenu(player: Player, face: BlockFace) {
        ports.getValue(face).openMenu(player)
    }

    override suspend fun handleNetworkLoaded(state: NetworkState) = applyArms(state)

    override suspend fun handleNetworkUpdate(state: NetworkState) = applyArms(state)

    private suspend fun applyArms(state: NetworkState) {
        val connected = state.getConnectedNodes(this).row(NetworkTypes.STORAGE).keys.toSet()

        // no storage connection left means no network rebuilt us, and nothing else would ever clear the
        // reference — see StorageNetworkGroup.lastTick, which catches this even when we are not notified
        if (connected.isEmpty())
            storageNetwork = null

        runTask {
            if (isEnabled)
                setArmFaces(connected)
        }
    }

    override fun getDrops(includeSelf: Boolean): List<ItemStack> {
        val drops = ArrayList(super.getDrops(includeSelf))
        for (port in ports.values)
            port.filterInventory.getItem(0)?.let(drops::add)
        return drops
    }

    /**
     * What a port is mounted on, once the difference between a chest and a Logistics storage unit has
     * been reduced to the four things this addon needs from either.
     */
    sealed interface Backing {

        val slots: Int
        val hasRoom: Boolean

        /**
         * Something equal for any two backings over the same underlying storage — see
         * [StorageProvider.storageIdentity].
         */
        val identity: Any

        /**
         * A capacity figure for the status readouts. Not every kind of storage reports one — see
         * [NetworkedBacking].
         */
        val totalCount: Long

        val usedTypes: Int
        val usedCount: Long

        fun collectInto(index: MutableMap<ItemType, Long>)
        fun countOf(type: ItemType): Long

        /** Stops at the first match, unlike counting. */
        fun holds(type: ItemType): Boolean

        fun insert(type: ItemType, amount: Long): Long
        fun extract(type: ItemType, amount: Long): Long

    }

    /**
     * A backing that is a row of slots holding at most a stack each — which is every container in the
     * game, and everything Nova's item network exposes. Each of the four questions the network asks
     * about the contents is then one pass over them.
     *
     * This addon's own barrels are the exception, and the reason this split exists: a barrel holds
     * thousands of one item and cannot describe itself in stacks without inventing slots it does not
     * have. See [BarrelBacking].
     */
    private abstract class SlottedBacking : Backing {

        abstract fun contents(): Array<ItemStack?>

        override val usedTypes: Int
            get() {
                val types = HashSet<ItemType>()
                for (stack in contents())
                    ItemType.of(stack)?.let(types::add)
                return types.size
            }

        override val usedCount: Long
            get() {
                var total = 0L
                for (stack in contents())
                    if (stack != null && !stack.isEmpty) total += stack.amount
                return total
            }

        override fun collectInto(index: MutableMap<ItemType, Long>) {
            for (stack in contents()) {
                val type = ItemType.of(stack) ?: continue
                index.merge(type, stack!!.amount.toLong()) { a, b -> a + b }
            }
        }

        override fun countOf(type: ItemType): Long {
            var total = 0L
            for (stack in contents())
                if (type.matches(stack)) total += stack!!.amount
            return total
        }

        override fun holds(type: ItemType): Boolean = contents().any(type::matches)

    }

    /**
     * A chest, barrel, shulker box or hopper.
     *
     * The reference points at the container's live backing array, so reads and writes are plain array
     * access — but it has to be *found* on the server thread, which is why [refreshContainers] runs
     * there. The window for a torn read is one tick and the worst case is a transfer that reports
     * slightly stale contents, the same trade Nova already makes to expose vanilla containers to its own
     * item network.
     */
    private class VanillaBacking(private val inventory: Inventory) : SlottedBacking() {

        // the block the container sits at — its centre point for a double chest, which is what makes a
        // connector on each half resolve to one identity rather than two. Bukkit builds a fresh wrapper
        // per call, so the object itself cannot be compared.
        override val identity: Any
            get() = inventory.location ?: inventory

        override val slots: Int
            get() = inventory.size

        override val hasRoom: Boolean
            get() = inventory.contents.any { it == null || it.isEmpty || it.amount < it.maxStackSize }

        override val totalCount: Long
            get() {
                var total = 0L
                for (stack in inventory.contents)
                    total += (stack?.takeUnless { it.isEmpty }?.maxStackSize ?: Material.STONE.maxStackSize).toLong()
                return total
            }

        override fun contents(): Array<ItemStack?> = inventory.contents

        override fun insert(type: ItemType, amount: Long): Long {
            val contents = inventory.contents
            val maxStack = type.maxStackSize
            var left = amount

            // top up partial stacks before opening a new slot, so a chest doesn't fragment
            for (slot in contents.indices) {
                if (left <= 0L) break
                val stack = contents[slot]
                if (!type.matches(stack)) continue

                val room = maxStack - stack!!.amount
                if (room <= 0) continue

                val transfer = min(left, room.toLong()).toInt()
                inventory.setItem(slot, stack.clone().apply { this.amount += transfer })
                left -= transfer
            }

            for (slot in contents.indices) {
                if (left <= 0L) break
                val stack = contents[slot]
                if (stack != null && !stack.isEmpty) continue

                val transfer = min(left, maxStack.toLong()).toInt()
                inventory.setItem(slot, type.createStack(transfer))
                left -= transfer
            }

            return amount - left
        }

        override fun extract(type: ItemType, amount: Long): Long {
            val contents = inventory.contents
            var extracted = 0L

            for (slot in contents.indices) {
                if (extracted >= amount) break
                val stack = contents[slot]
                if (!type.matches(stack)) continue

                val taken = min(amount - extracted, stack!!.amount.toLong()).toInt()
                val remaining = stack.amount - taken
                inventory.setItem(slot, if (remaining <= 0) null else stack.clone().apply { this.amount = remaining })
                extracted += taken
            }

            return extracted
        }

    }

    /**
     * Anything Nova considers storage — a Logistics storage unit above all, but equally a Nova machine's
     * buffer or another addon's.
     *
     * [NetworkedInventory] is the interface Nova's own item network drives from its async ticker, so
     * unlike a vanilla container this needs no trip through the server thread at all: the reference is
     * resolved once and used straight from the storage network's tick.
     *
     * It reports no capacity, only whether it is full, which is why [StorageProvider.hasRoom] exists
     * separately from comparing counts. [totalCount] is therefore a lower bound rather than a real
     * figure — a storage unit holding ten thousand items genuinely does not say how many more it takes.
     *
     * This is also the one backing whose count can still overstate what [extract] will produce, because
     * [extract] skips a slot the owner refuses to give up and counting cannot ask without firing that
     * owner's pre-update handlers — which is a side effect, not a question. Left as it is: a `BUFFER`
     * container that vetoes its own extraction is not something Nova's own inventories do, and if one
     * ever turns up, [NetworkView.take][it.sgdc3.smartstorage.network.NetworkView.take] says so out loud
     * rather than letting the difference go quietly into somebody's chest.
     */
    private class NetworkedBacking(private val inventory: NetworkedInventory) : SlottedBacking() {

        // a tile entity holds one instance of its inventory, so two connectors reaching the same
        // neighbour genuinely get the same object here
        override val identity: Any
            get() = inventory

        override val slots: Int
            get() = inventory.size

        override val hasRoom: Boolean
            get() = !inventory.isFull()

        override val totalCount: Long
            get() = contents().sumOf { (it?.takeUnless(ItemStack::isEmpty)?.amount ?: 0).toLong() } +
                if (hasRoom) 1L else 0L

        // Nova fills the destination with empty stacks rather than nulls, so it is handed a non-null
        // array and the empties are folded back to null on the way out
        override fun contents(): Array<ItemStack?> {
            val destination = Array(inventory.size) { ItemStack.empty() }
            inventory.copyContents(destination)
            return Array(destination.size) { destination[it].takeUnless(ItemStack::isEmpty) }
        }

        override fun insert(type: ItemType, amount: Long): Long {
            val offered = min(amount, Int.MAX_VALUE.toLong()).toInt()
            return (offered - inventory.add(type.createStack(1), offered)).toLong()
        }

        override fun extract(type: ItemType, amount: Long): Long {
            val contents = contents()
            var extracted = 0L

            for (slot in contents.indices) {
                if (extracted >= amount) break
                val stack = contents[slot]
                if (!type.matches(stack)) continue

                val taken = min(amount - extracted, stack!!.amount.toLong()).toInt()
                if (taken <= 0 || !inventory.canTake(slot, taken)) continue

                inventory.take(slot, taken)
                extracted += taken
            }

            return extracted
        }

    }

    /**
     * A tank, seen through Nova's own [NetworkedFluidContainer].
     *
     * Much simpler than its item counterpart, and for a reason worth stating: a fluid container holds
     * *one* type at a time and reports it, so there is no contents array to walk, no slot to address and
     * no snapshot to keep. Everything here is a field read on the container.
     *
     * The same threading trade as [NetworkedBacking]: this is the interface Nova's fluid network drives
     * from its own ticker, so the reference is resolved once and used straight from ours, and the race
     * against another addon's group tick is accepted rather than solved — see the class KDoc.
     */
    private class FluidBacking(private val container: NetworkedFluidContainer) : FluidBackingSource {

        override val identity: Any
            get() = container

        override val usedAmount: Long
            get() = container.amount

        override val totalAmount: Long
            get() = container.capacity

        override fun amountOf(type: FluidType): Long =
            if (container.type == type) container.amount else 0L

        override fun collectFluidsInto(index: MutableMap<FluidType, Long>) {
            val type = container.type ?: return
            val amount = container.amount
            if (amount > 0L)
                index.merge(type, amount) { a, b -> a + b }
        }

        override fun insertFluid(type: FluidType, amount: Long): Long {
            if (!container.accepts(type))
                return 0L

            return container.addFluid(type, amount)
        }

        override fun extractFluid(type: FluidType, amount: Long): Long {
            // takeFluid has no type parameter: it takes from whatever is in there, so the check has to
            // happen here or a request for water would come back with lava
            if (container.type != type)
                return 0L

            return container.takeFluid(amount)
        }

    }

    /**
     * What a port is mounted on for fluids. One implementation today; an interface anyway, so that the
     * port can hold "a tank of some kind" without knowing which.
     */
    sealed interface FluidBackingSource {

        val identity: Any
        val usedAmount: Long
        val totalAmount: Long

        fun amountOf(type: FluidType): Long
        fun collectFluidsInto(index: MutableMap<FluidType, Long>)
        fun insertFluid(type: FluidType, amount: Long): Long
        fun extractFluid(type: FluidType, amount: Long): Long

    }

    /**
     * One of this addon's own barrels.
     *
     * Read directly rather than through the one-slot view Nova's item network gets, because that view
     * cannot say what a barrel is for: it reports a stack of cobblestone where the barrel holds two
     * thousand, and a terminal that showed 64 of them would be worse than useless. Everything here is
     * a field read on the barrel.
     */
    private class BarrelBacking(private val barrel: StorageBarrel) : Backing {

        override val identity: Any
            get() = barrel

        // one type, so one "slot" as far as the readouts are concerned
        override val slots: Int
            get() = 1

        override val hasRoom: Boolean
            get() = barrel.hasRoom

        override val totalCount: Long
            get() = barrel.capacity

        override val usedTypes: Int
            get() = if (barrel.storedType != null) 1 else 0

        override val usedCount: Long
            get() = barrel.storedAmount

        override fun collectInto(index: MutableMap<ItemType, Long>) {
            val type = barrel.storedType ?: return
            val amount = barrel.storedAmount
            if (amount > 0L)
                index.merge(type, amount) { a, b -> a + b }
        }

        override fun countOf(type: ItemType): Long = barrel.countOf(type)

        override fun holds(type: ItemType): Boolean = barrel.holds(type)

        override fun insert(type: ItemType, amount: Long): Long = barrel.insert(type, amount)

        override fun extract(type: ItemType, amount: Long): Long = barrel.extract(type, amount)

    }

    /**
     * A whole wall of barrels, through the [BarrelController] that speaks for it.
     *
     * A connector resolves a *claimed* barrel to this as well, so putting one against the controller
     * and putting one against any barrel of its wall produce the same [identity] and the network keeps
     * exactly one of them. That is not a nicety: two providers over the same storage promise twice what
     * they can deliver, which is how items get made out of nothing.
     */
    private class BarrelWallBacking(private val controller: BarrelController) : Backing {

        override val identity: Any
            get() = controller

        override val slots: Int
            get() = controller.barrels.size

        override val hasRoom: Boolean
            get() = controller.hasRoom

        override val totalCount: Long
            get() = controller.totalCount

        override val usedTypes: Int
            get() = controller.usedTypes

        override val usedCount: Long
            get() = controller.usedCount

        override fun collectInto(index: MutableMap<ItemType, Long>) = controller.collectInto(index)

        override fun countOf(type: ItemType): Long = controller.countOf(type)

        override fun holds(type: ItemType): Boolean = controller.holds(type)

        override fun insert(type: ItemType, amount: Long): Long = controller.insert(type, amount)

        override fun extract(type: ItemType, amount: Long): Long = controller.extract(type, amount)

    }

    /**
     * One side of the connector, and everything the network knows about the storage mounted there.
     */
    inner class ContainerPort(private val face: BlockFace) : StorageProvider, FluidProvider {

        @Volatile
        var backing: Backing? = null

        @Volatile
        var fluidBacking: FluidBackingSource? = null

        /**
         * The three settings a player owns, and the one place in this class not marked `@Volatile`
         * despite being written from the main thread and read from the network's.
         *
         * That is not an oversight and not a hazard: a delegated property has no field of its own to
         * annotate, and it does not need one. Nova ticks networks from `runBlocking` on the main thread,
         * so a menu click cannot land inside a tick — the same invariant
         * [NetworkView.take][it.sgdc3.smartstorage.network.NetworkView.take] spells out and depends on.
         * The `@Volatile` markers elsewhere in this file are belt over the same braces.
         */
        private val priorityValue = storedValue("priority_${face.name}") { DEFAULT_PRIORITY }
        override var priority: Int by priorityValue

        private val insertValue = storedValue("insert_${face.name}") { true }
        var allowInsert: Boolean by insertValue

        private val extractValue = storedValue("extract_${face.name}") { true }
        var allowExtract: Boolean by extractValue

        /**
         * A side with both directions turned off is not storage at all: no port is drawn, the container
         * is left out of the network, and the block state stops counting the side as occupied — which
         * in turn drops the chain behind the model if it was only justified by this port.
         */
        val isActive: Boolean
            get() = (backing != null || fluidBacking != null) && (allowInsert || allowExtract)

        private var filter: ItemFilter<*>? by storedValue("filter_${face.name}")
        val filterInventory = VirtualInventory(null, 1, arrayOfNulls(1), intArrayOf(1))

        private var menu: PortMenu? = null

        init {
            // priorities used to run negative; the menus now show them with Nova's numbered GUI item,
            // which has no model below zero
            if (priority !in PRIORITY_RANGE)
                priority = priority.coerceIn(PRIORITY_RANGE)

            filter?.let { filterInventory.setItem(SELF_UPDATE_REASON, 0, it.toItemStack()) }
            filterInventory.addPreUpdateHandler(::handleFilterUpdate)
        }

        fun openMenu(player: Player) {
            val menu = this.menu ?: PortMenu().also { this.menu = it }
            menu.open(player)
        }

        private fun handleFilterUpdate(event: ItemPreUpdateEvent) {
            if (event.updateReason == SELF_UPDATE_REASON)
                return

            val newItem = event.newItem
            if (newItem == null) {
                filter = null
                return
            }

            if (newItem.isItemFilter()) {
                filter = newItem.getItemFilter()
            } else {
                event.isCancelled = true
            }
        }

        /**
         * The filter gates what may be *stored* here. Whatever is already in the container stays visible
         * and extractable — silently hiding a player's items would be a much worse surprise than a chest
         * that refuses new ones.
         */
        private fun allows(type: ItemType): Boolean =
            filter?.allows(type.stack) != false

        //<editor-fold desc="StorageProvider", defaultstate="collapsed">

        // whichever kind of storage is on this side; a side is only ever one of the two, so there is no
        // ordering question here, only a fallback for a side with nothing on it
        override val storageIdentity: Any
            get() = backing?.identity ?: fluidBacking?.identity ?: this

        override val cellCount: Int
            get() = if (backing != null) 1 else 0

        override val usedTypes: Int
            get() = backing?.usedTypes ?: 0

        override val totalTypes: Int
            get() = backing?.slots ?: 0

        override val usedCount: Long
            get() = backing?.usedCount ?: 0L

        override val totalCount: Long
            get() = backing?.totalCount ?: 0L

        override val hasRoom: Boolean
            get() = allowInsert && backing?.hasRoom == true

        override fun collectInto(index: MutableMap<ItemType, Long>) {
            backing?.collectInto(index)
        }

        override fun holds(type: ItemType): Boolean = backing?.holds(type) == true

        override fun countOf(type: ItemType): Long = backing?.countOf(type) ?: 0L

        /**
         * Nothing, once this side has been told to keep its contents.
         *
         * [countOf] deliberately still answers with everything, because a terminal showing what is in a
         * chest is right even when the network may not take it out — silently hiding a player's items
         * would be the worse surprise. But a *promise* to a foreign network has to be one this side will
         * keep, and a side with extraction off keeps none. See [StorageProvider.extractableCountOf].
         */
        override fun extractableCountOf(type: ItemType): Long =
            if (allowExtract) countOf(type) else 0L

        override fun collectExtractableInto(index: MutableMap<ItemType, Long>) {
            if (allowExtract)
                collectInto(index)
        }

        override fun insert(type: ItemType, amount: Long): Long {
            if (!allowInsert || !allows(type))
                return 0L

            return backing?.insert(type, amount) ?: 0L
        }

        override fun extract(type: ItemType, amount: Long): Long {
            if (!allowExtract)
                return 0L

            return backing?.extract(type, amount) ?: 0L
        }

        //</editor-fold>

        //<editor-fold desc="FluidProvider", defaultstate="collapsed">

        override val usedAmount: Long
            get() = fluidBacking?.usedAmount ?: 0L

        override val totalAmount: Long
            get() = fluidBacking?.totalAmount ?: 0L

        override val hasFluidRoom: Boolean
            get() = allowInsert && fluidBacking?.let { it.usedAmount < it.totalAmount } == true

        override fun collectFluidsInto(index: MutableMap<FluidType, Long>) {
            fluidBacking?.collectFluidsInto(index)
        }

        override fun amountOf(type: FluidType): Long = fluidBacking?.amountOf(type) ?: 0L

        /**
         * The same rule as [extractableCountOf], and it matters more here: an over-promised fluid is not
         * merely created, it makes Nova's distributor throw halfway through a transfer it has already
         * half performed.
         */
        override fun extractableAmountOf(type: FluidType): Long =
            if (allowExtract) amountOf(type) else 0L

        /**
         * The filter gates fluids exactly as it gates items — by the bucket that carries the fluid, so
         * a filter holding a water bucket makes that side a water tank.
         */
        override fun insertFluid(type: FluidType, amount: Long): Long {
            if (!allowInsert || filter?.allows(type.bucket) == false)
                return 0L

            return fluidBacking?.insertFluid(type, amount) ?: 0L
        }

        override fun extractFluid(type: FluidType, amount: Long): Long {
            if (!allowExtract)
                return 0L

            return fluidBacking?.extractFluid(type, amount) ?: 0L
        }

        //</editor-fold>

        /**
         * Configures this side alone. Reached by right-clicking the port that faces it, or from the
         * summary in the connector's own menu.
         */
        private inner class PortMenu {

            private val statusItem = ClickableItem({ statusIcon() })
            private val insertItem = ClickableItem(
                { toggleIcon(allowInsert, DefaultGuiItems.BLUE_BTN, "menu.smartstorage.port.insert") },
                { _, _, _ -> toggle { allowInsert = !allowInsert } }
            )
            private val extractItem = ClickableItem(
                { toggleIcon(allowExtract, DefaultGuiItems.ORANGE_BTN, "menu.smartstorage.port.extract") },
                { _, _, _ -> toggle { allowExtract = !allowExtract } }
            )

            private val priorityItem = ClickableItem({ priorityIcon(priority) })

            private val gui = Gui.builder()
                .setStructure(
                    ". . . . . . . . .",
                    ". f . n x . m v p",
                    ". i . . . . . . ."
                )
                .addIngredient('f', filterInventory, GuiItems.STORAGE_FILTER_PLACEHOLDER)
                .addIngredient('n', insertItem)
                .addIngredient('x', extractItem)
                .addIngredient('i', statusItem)
                .addIngredient('v', priorityItem)
                .addIngredient('m', RemoveNumberItem({ PRIORITY_RANGE }, { priority }, ::setPriority, "menu.smartstorage.priority_down"))
                .addIngredient('p', AddNumberItem({ PRIORITY_RANGE }, { priority }, ::setPriority, "menu.smartstorage.priority_up"))
                .build()

            fun open(player: Player) {
                val window = Window.builder()
                    // without the texture's own title component the menu falls back to a plain chest,
                    // and the title goes in unstyled because a window title is drawn dark on the panel
                    .setTitle(GuiTextures.STORAGE_CONNECTOR.getTitle(faceName()))
                    .setUpperGui(gui)
                    .build(player)

                menuContainer.registerWindow(window)
                window.open()
            }

            private fun setPriority(value: Int) {
                priority = value
                priorityItem.notifyWindows()
                statusItem.notifyWindows()
            }

            /**
             * Turning both directions off retires the port, so the change has to reach the model, the
             * hitboxes and the block state — [refreshContainers] is what knows how to do all three.
             */
            private inline fun toggle(change: () -> Unit) {
                change()
                refreshContainers()
                statusItem.notifyWindows()
                insertItem.notifyWindows()
                extractItem.notifyWindows()
            }

            /**
             * Nova's own side config colours, because these switches mean exactly what its do: blue for
             * what goes in, orange for what comes out, grey for a direction that is closed.
             */
            private fun toggleIcon(on: Boolean, onItem: NovaItem, key: String): ItemBuilder =
                (if (on) onItem else DefaultGuiItems.GRAY_BTN).createClientsideItemBuilder().setName(
                    Component.translatable(
                        key,
                        if (on) NamedTextColor.GREEN else NamedTextColor.GRAY,
                        Component.translatable(if (on) "menu.smartstorage.port.on" else "menu.smartstorage.port.off")
                    ).withoutPreFormatting()
                )

            private fun statusIcon(): ItemBuilder = icon()

        }

        /**
         * Left unstyled on purpose. It is the window title as well as an item name, and a window title
         * is drawn dark on the panel — forcing the white an item name needs turns it invisible there.
         */
        fun faceName(): Component = Component.translatable(
            "menu.smartstorage.side",
            Component.translatable("menu.smartstorage.face.${face.name.lowercase()}")
        )

        /**
         * Shown both as the port menu's status and as this side's entry in the connector's own menu, so
         * the two can never disagree about what a side is doing.
         */
        fun icon(): ItemBuilder {
            val builder = ItemBuilder(
                when {
                    isActive -> Material.CHEST
                    backing != null -> Material.BARRIER
                    else -> Material.GRAY_STAINED_GLASS_PANE
                }
            )
            builder.setName(faceName().withoutPreFormatting())

            val lore = ArrayList<Component>()
            lore += when {
                backing == null ->
                    Component.translatable("menu.smartstorage.connector.empty", NamedTextColor.RED)
                        .withoutPreFormatting()

                !isActive ->
                    Component.translatable("menu.smartstorage.port.disabled", NamedTextColor.RED)
                        .withoutPreFormatting()

                else -> Component.translatable(
                    "menu.smartstorage.connector.slots",
                    NamedTextColor.GRAY,
                    Component.text(usedTypes, NamedTextColor.GREEN),
                    Component.text(totalTypes, NamedTextColor.GREEN)
                ).withoutPreFormatting()
            }

            lore += Component.translatable(
                "menu.smartstorage.port.directions",
                NamedTextColor.GRAY,
                Component.translatable(
                    if (allowInsert) "menu.smartstorage.port.on" else "menu.smartstorage.port.off",
                    if (allowInsert) NamedTextColor.GREEN else NamedTextColor.RED
                ),
                Component.translatable(
                    if (allowExtract) "menu.smartstorage.port.on" else "menu.smartstorage.port.off",
                    if (allowExtract) NamedTextColor.GREEN else NamedTextColor.RED
                )
            ).withoutPreFormatting()

            lore += Component.translatable(
                "menu.smartstorage.priority",
                NamedTextColor.GRAY,
                Component.text(priority, NamedTextColor.GREEN)
            ).withoutPreFormatting()

            builder.setLore(lore)
            return builder
        }

    }

    @TileEntityMenuClass
    inner class StorageConnectorMenu : GlobalTileEntityMenu(GuiTextures.STORAGE_CONNECTOR) {

        private val statusItem = ClickableItem({ statusIcon() })

        private val faceItems = CUBE_FACES.map { face ->
            val port = ports.getValue(face)
            ClickableItem({ port.icon() }, { _, player, _ -> port.openMenu(player) })
        }

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . .",
                ". i . 1 2 3 4 5 6",
                ". . . . . . . . ."
            )
            .addIngredient('i', statusItem)
            .addIngredient('1', faceItems[0])
            .addIngredient('2', faceItems[1])
            .addIngredient('3', faceItems[2])
            .addIngredient('4', faceItems[3])
            .addIngredient('5', faceItems[4])
            .addIngredient('6', faceItems[5])
            .build()

        fun update() {
            statusItem.notifyWindows()
            faceItems.forEach(ClickableItem::notifyWindows)
        }

        private fun statusIcon(): ItemBuilder {
            val builder = ItemBuilder(Material.CHEST)
            builder.setName(Component.translatable("menu.smartstorage.connector.title").withoutPreFormatting())

            val mounted = storageProviders.size
            builder.setLore(
                listOf(
                    if (mounted > 0)
                        Component.translatable(
                            "menu.smartstorage.connector.attached",
                            NamedTextColor.GREEN,
                            Component.text(mounted, NamedTextColor.GREEN)
                        ).withoutPreFormatting()
                    else
                        Component.translatable("menu.smartstorage.connector.detached", NamedTextColor.RED)
                            .withoutPreFormatting()
                )
            )
            return builder
        }

    }

}
