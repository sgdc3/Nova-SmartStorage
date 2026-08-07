package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.networkStatusIcon
import it.sgdc3.smartstorage.gui.priorityIcon
import it.sgdc3.smartstorage.network.DEFAULT_PRIORITY
import it.sgdc3.smartstorage.network.FluidGateway
import it.sgdc3.smartstorage.network.NetworkFluidView
import it.sgdc3.smartstorage.network.NetworkView
import it.sgdc3.smartstorage.network.PRIORITY_RANGE
import it.sgdc3.smartstorage.network.StorageEndPoint
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.network.TransferBudget
import it.sgdc3.smartstorage.registry.Blocks.STORAGE_INTERFACE
import it.sgdc3.smartstorage.registry.GuiItems
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.Models
import it.sgdc3.smartstorage.registry.NetworkTypes
import it.sgdc3.smartstorage.util.getItemFilter
import it.sgdc3.smartstorage.util.isItemFilter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.enumMap
import xyz.xenondevs.commons.collections.toEnumSet
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.inventory.VirtualInventory
import xyz.xenondevs.invui.inventory.event.ItemPreUpdateEvent
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.window.Window
import xyz.xenondevs.nova.addon.simpleupgrades.gui.OpenUpgradesItem
import xyz.xenondevs.nova.addon.simpleupgrades.registry.UpgradeTypes
import xyz.xenondevs.nova.addon.simpleupgrades.storedUpgradeHolder
import xyz.xenondevs.nova.config.entry
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
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.ContainerEndPointDataHolder
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes.FLUID
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes.ITEM
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.holder.FluidHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemFilter
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.item.DefaultGuiItems
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.util.NumberFormatUtils
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

private val EXPOSED_SLOTS by STORAGE_INTERFACE.config.entry<Int>("exposed_slots")
private val BASE_ITEM_TRANSFER by STORAGE_INTERFACE.config.entry<Double>("base_item_transfer")
private val BASE_FLUID_TRANSFER by STORAGE_INTERFACE.config.entry<Double>("base_fluid_transfer")

/**
 * How often the neighbours are re-read for the menu icons. Nothing routes on this, so a second is well
 * inside "instant" and there is no reason to pay for it every tick — but it is a knob like every other
 * scan interval in this addon, rather than the one that was nailed shut.
 */
private val NEIGHBOUR_RESCAN_TICKS by STORAGE_INTERFACE.config.entry<Int>("neighbour_rescan_ticks")

/**
 * Bridges a storage network into Nova's item and fluid networks.
 *
 * Sitting on all three at once, it exposes everything the storage network holds as a plain inventory and
 * a pair of tanks. Place one against a chest and items flow both ways; run Logistics cables into it and
 * you get routing for free. This is why the addon needs no import or export buses of its own.
 *
 * ## Everything is per face
 *
 * A side has its own directions, its own filters and its own fluid — nothing here is set for the block
 * as a whole. An interface wedged between a furnace and a pipe is doing two unrelated jobs, and a single
 * pair of filters could only describe one of them.
 *
 * The item filters are Nova's own [ItemHolder.insertFilters][xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder.insertFilters]
 * and `extractFilters`, so Nova applies them per face in its own distributor, persists them and drops
 * them when the block breaks. This addon only puts a slot in front of them.
 *
 * There is deliberately no side config menu. It would be a second way to set the same things, and a
 * second way that does not know about the rule below.
 *
 * ## Nothing leaves a side that has not been told what may leave
 *
 * The two directions are not symmetrical, and the asymmetry is the point. An unfiltered *input* is a
 * mistake that stores something awkwardly and it is still there afterwards. An unfiltered *output* is a
 * hole: put a side against a hopper, forget the filter, and the network hands over everything it has, in
 * order, until it is empty — which noticing quickly does not undo.
 *
 * So a face's extract filter is an allow-list that has to exist, and [enforceExtractFilters] holds the
 * config to it: turning extraction on for a side with no filter turns straight back off, and pulling the
 * filter out of a side that was extracting closes it.
 *
 * **This is about items only.** Fluids used to be governed by the same slot, named by the bucket that
 * carries them, and it was the wrong shape for them: a filter is a list of the many things that may
 * pass, while a fluid side already deals in exactly one of the two fluids there are — the picker on the
 * side says which. Requiring a bucket in the filter as well made one slot mean two unrelated things
 * depending on what was mounted, and gave a player who had filtered a chest a tank that quietly stopped
 * filling. A fluid side is governed by its own two switches and its picker; extraction still starts
 * closed, so nothing leaves before somebody opens it.
 *
 * ## What it looks like
 *
 * An arm towards each device on the *storage* network and a port against each endpoint on the item or
 * fluid network — the chest, machine or tank it feeds. It has no facing of its own, so a single
 * interface wedged between two machines serves both.
 */
class StorageInterface(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : StorageHub(pos, state, data), StorageEndPoint, FluidGateway {

    override val portModel = Models.INTERFACE_ATTACHMENT
    override val portModelOff = Models.INTERFACE_ATTACHMENT_OFF

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    private val upgradeHolder = storedUpgradeHolder(UpgradeTypes.SPEED)

    /**
     * What this interface will move in one network tick, per resource and per direction.
     *
     * A storage interface is the seam between a virtual system that holds everything and a world that
     * moves things one at a time, and without a rate of its own it was not a seam at all: Nova takes a
     * network's throughput from its *cables*, and an interface bolted straight onto a tank has none — so
     * the whole system emptied into that tank in a single tick. It is now slow out of the box and as
     * fast as a player has paid for. See [TransferBudget].
     */
    private val itemInput = TransferBudget()
    private val itemOutput = TransferBudget()
    private val fluidInput = TransferBudget()
    private val fluidOutput = TransferBudget()

    private val networkView = NetworkView(this, uuid, EXPOSED_SLOTS, itemInput, itemOutput)
    private val itemHolder = storedItemHolder(networkView to NetworkConnectionType.BUFFER)

    /**
     * One tank per fluid Nova has — see [NetworkFluidView] for why it cannot be one tank for both.
     *
     * The UUIDs are derived from this block's own so that they stay put across restarts: Nova stores a
     * face's chosen container by UUID, and a random one per load would scramble every side config.
     */
    private val fluidViews: List<NetworkFluidView> = FluidType.entries.map { fluid ->
        NetworkFluidView(
            this,
            UUID.nameUUIDFromBytes("$uuid:${fluid.name}".toByteArray()),
            fluid,
            fluidInput,
            fluidOutput
        )
    }

    /**
     * Inputs open, outputs closed, the same as the item side — and the same rule keeps them closed until
     * a filter names what may come out.
     */
    private val fluidHolder = storedFluidHolder(
        fluidViews.first() to NetworkConnectionType.BUFFER,
        *fluidViews.drop(1).map { it to NetworkConnectionType.BUFFER }.toTypedArray(),
        defaultConnectionConfig = { CUBE_FACES.associateWithTo(enumMap()) { NetworkConnectionType.INSERT } }
    )

    /**
     * One per side, kept rather than rebuilt per click so an open window keeps working after somebody
     * else changes the same side.
     */
    private val portMenus = enumMap<BlockFace, PortMenu>()

    /**
     * Sides with something against them on either network, and the subset of those actually being
     * served. Both are written by [applyConnections] and read by the menus, which is the only way an
     * icon can tell "nothing there" from "there, but switched off".
     */
    @Volatile
    private var attachedFaces: Set<BlockFace> = emptySet()

    @Volatile
    private var servedFaces: Set<BlockFace> = emptySet()

    init {
        holders += storageHolder
    }

    /**
     * Tells Nova which faces are still worth connecting, for every face this block has closed.
     *
     * A closed face is not merely idle to Nova: `FluidNetworkChannel` and `ItemDistributorBuilder` both
     * **throw** on a face they hold a connection to whose connection type is `NONE`, and that exception
     * aborts the whole build — every dirty network of every type, in every chunk being processed. One
     * side switched off on one interface takes down the storage network with it, and the first thing a
     * player sees is that their controller has gone dark for no reason they can find.
     *
     * Called for every closed face rather than only the ones just changed, because a world may already
     * hold the inconsistency: an earlier version of [enforceExtractFilters] closed fluid sides without
     * telling anyone, and what it left behind is persisted in Nova's own network state, not in ours.
     * There is no repairing that from inside the build — it never gets far enough to notify us — so this
     * also runs from [handleEnable], which does not depend on a network existing.
     */
    private suspend fun syncAllowedFaces(state: NetworkState) {
        for (face in CUBE_FACES) {
            if (itemHolder.connectionConfig[face] == NetworkConnectionType.NONE)
                state.handleEndPointAllowedFacesChange(this, ITEM, face)

            if (fluidHolder.connectionConfig[face] == NetworkConnectionType.NONE)
                state.handleEndPointAllowedFacesChange(this, FLUID, face)
        }
    }

    override fun handleEnable() {
        super.handleEnable()
        NetworkManager.queueWrite(pos.chunkPos, ::syncAllowedFaces)
    }

    override suspend fun handleNetworkLoaded(state: NetworkState) = applyConnections(state)

    override suspend fun handleNetworkUpdate(state: NetworkState) = applyConnections(state)

    /**
     * Keeps the "is there anything on this side" readout honest.
     *
     * It cannot be left to network updates, because a side that has been switched off forms no
     * connection — so putting a chest against it is not a change Nova has any reason to tell us about,
     * and the icon would go on saying the side is empty until something unrelated happened.
     *
     * Six lookups in a map, once a second. Not gated on an open menu because a port window is not one of
     * the menus [menuContainer] counts, and the whole point is the icon inside it.
     */
    override fun handleTick() {
        refillBudgets()

        // the lamp in the menu says what the core's own light says, so one signal redraws both
        if (setPowered(storageNetwork?.isOnline == true))
            menuContainer.forEachMenu(StorageInterfaceMenu::update)

        if (serverTick % max(1, NEIGHBOUR_RESCAN_TICKS) != 0)
            return

        NetworkManager.queueRead(pos.chunkPos) { state ->
            val attached = neighbours(state)
            if (attached == attachedFaces)
                return@queueRead

            attachedFaces = attached
            runTask {
                menuContainer.forEachMenu(StorageInterfaceMenu::update)
                portMenus.values.forEach(PortMenu::update)
            }
        }
    }

    /**
     * Arms follow the storage network, ports follow the item *and* fluid networks — and only where the
     * neighbour is an endpoint, because a cable already draws its own attachment against us and two
     * plates pressed face to face would be one too many.
     *
     * A side whose connection config allows neither direction on either network gets no port. There is
     * nothing flowing through it, so drawing a nozzle against that chest would be a lie — and dropping
     * it also frees the block state, which is what decides whether the chain behind the model is still
     * earned.
     */
    private suspend fun applyConnections(state: NetworkState) {
        // The return value is not decoration. Closing a side that had nothing but extraction open leaves
        // it at NONE, and a face at NONE is a face Nova must stop counting as connected — its own
        // network builder throws outright on one it still holds a connection to. Telling it is the whole
        // point of the flag, and for a long time nothing here read it.
        if (enforceExtractFilters())
            syncAllowedFaces(state)

        val connected = state.getConnectedNodes(this)
        val arms = connected.row(NetworkTypes.STORAGE).keys.toEnumSet()

        val ports = HashSet<BlockFace>()
        collectPorts(connected.row(ITEM), itemHolder.connectionConfig, ports)
        collectPorts(connected.row(FLUID), fluidHolder.connectionConfig, ports)

        attachedFaces = neighbours(state)
        servedFaces = ports

        // no storage connection left means no network rebuilt us, and nothing else would ever clear the
        // reference — see StorageNetworkGroup.lastTick, which catches this even when we are not notified
        if (arms.isEmpty())
            storageNetwork = null

        runTask {
            if (!isEnabled)
                return@runTask

            setArmFaces(arms)
            setPortFaces(ports)
            menuContainer.forEachMenu(StorageInterfaceMenu::update)
        }
    }

    /**
     * The sides with something against them this interface *could* serve — an end point that holds items
     * or fluids, whether or not this side is currently willing to talk to it.
     *
     * Read from the node map rather than from the connected ones, and that distinction is the whole
     * point: a connection only exists where the config allows one, so asking which nodes are *connected*
     * makes a chest disappear the moment you switch its side off. The icon would then jump from "serving
     * this" straight to "nothing here", and the state it was meant to show — something there, switched
     * off — could never be reached.
     */
    private fun neighbours(state: NetworkState): Set<BlockFace> =
        state.getNearbyNodes(pos, CUBE_FACES)
            .filterValues { node ->
                node is NetworkEndPoint && node.holders.any { it is ItemHolder || it is FluidHolder }
            }
            .keys
            .toEnumSet()

    /**
     * Closes the outgoing direction of any side that has not been told what may go out.
     *
     * Called wherever the config or a filter can have moved, and once more whenever the network is
     * rebuilt — so this is a rule the block keeps rather than one its menu merely declines to break.
     *
     * @return whether anything changed, so a caller can decide whether Nova needs telling
     */
    private fun enforceExtractFilters(): Boolean {
        var changed = false

        for (face in CUBE_FACES) {
            val items = itemHolder.connectionConfig[face] ?: continue
            if (items.extract && itemHolder.extractFilters[face] == null) {
                itemHolder.connectionConfig[face] = NetworkConnectionType.of(items.insert, false)
                changed = true
            }
        }

        return changed
    }

    /**
     * Adds every face of [neighbours] that is an end point and has a direction open in [config].
     */
    private fun collectPorts(
        neighbours: Map<BlockFace, *>,
        config: Map<BlockFace, NetworkConnectionType>,
        into: MutableSet<BlockFace>
    ) {
        for ((face, node) in neighbours) {
            if (node !is NetworkEndPoint)
                continue

            val type = config[face] ?: continue
            if (type.insert || type.extract)
                into += face
        }
    }

    override fun handleDisable() {
        storageNetwork = null
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        storageNetwork = null
        super.handleBreak(ctx)
    }

    override fun openPortMenu(player: Player, face: BlockFace) {
        portMenu(face).open(player)
    }

    private fun portMenu(face: BlockFace): PortMenu = portMenus.getOrPut(face) { PortMenu(face) }

    /**
     * How much this interface moves per network tick, items and fluid alike, at its current speed.
     *
     * One Speed Upgrade means one thing here rather than two, because a player installing it means "make
     * this thing faster" and does not care that what is passing through happens to be a liquid.
     */
    private fun itemsPerTick(): Long = rate(BASE_ITEM_TRANSFER)

    private fun fluidPerTick(): Long = rate(BASE_FLUID_TRANSFER)

    private fun rate(base: Double): Long =
        (base * upgradeHolder.getValue(UpgradeTypes.SPEED)).roundToLong().coerceAtLeast(0L)

    /**
     * Hands each direction its allowance for the coming tick.
     *
     * Set rather than accumulated: an interface nobody used for a minute has not banked a minute's worth
     * of throughput, or the first thing to touch it would empty the system after all.
     */
    private fun refillBudgets() {
        val items = itemsPerTick()
        itemInput.refill(items)
        itemOutput.refill(items)

        val fluid = fluidPerTick()
        fluidInput.refill(fluid)
        fluidOutput.refill(fluid)
    }

    /**
     * What a side is doing, shown both as its own menu's summary and as its entry in the block's menu,
     * so the two can never disagree.
     */
    private fun faceIcon(face: BlockFace): ItemBuilder {
        val items = itemHolder.connectionConfig[face] ?: NetworkConnectionType.NONE
        val fluids = fluidHolder.connectionConfig[face] ?: NetworkConnectionType.NONE
        val open = items != NetworkConnectionType.NONE || fluids != NetworkConnectionType.NONE
        val attached = face in attachedFaces

        // the same three states a storage connector's side has, and the same three icons: something
        // being served, something there that has been switched off, and nothing there at all
        val builder = ItemBuilder(
            when {
                attached && open -> Material.HOPPER
                attached -> Material.BARRIER
                else -> Material.GRAY_STAINED_GLASS_PANE
            }
        )
        builder.setName(faceName(face).withoutPreFormatting())

        val lore = ArrayList<Component>()
        lore += when {
            !attached ->
                Component.translatable("menu.smartstorage.interface.empty", NamedTextColor.RED)
                    .withoutPreFormatting()

            !open ->
                Component.translatable("menu.smartstorage.port.disabled", NamedTextColor.RED)
                    .withoutPreFormatting()

            else -> directions("menu.smartstorage.port.directions", items)
        }

        if (attached && open)
            lore += directions("menu.smartstorage.port.fluid_directions", fluids)

        val fluid = (fluidHolder.containerConfig[face] as? NetworkFluidView)?.fluid
        if (fluid != null && fluids != NetworkConnectionType.NONE) {
            lore += Component.translatable(
                "menu.smartstorage.port.fluid_kind",
                NamedTextColor.GRAY,
                Component.translatable(fluid.localizedName, NamedTextColor.GREEN)
            ).withoutPreFormatting()
        }

        lore += Component.translatable(
            "menu.smartstorage.priority",
            NamedTextColor.GRAY,
            Component.text(priorityOf(face), NamedTextColor.GREEN)
        ).withoutPreFormatting()

        builder.setLore(lore)
        return builder
    }

    /**
     * What a side is worth to Nova's own routing.
     *
     * Nova keeps four numbers per face — insert and extract, items and fluids — and this addon shows
     * one, because four switches that nobody sets independently is a menu rather than a control. Reading
     * the item insert map is arbitrary only in the sense that all four are held equal by [setPriority].
     */
    private fun priorityOf(face: BlockFace): Int =
        itemHolder.insertPriorities[face] ?: DEFAULT_PRIORITY

    /**
     * Left unstyled on purpose: it is the window title as well as an item name, and a window title is
     * drawn dark on the panel — forcing the white an item name needs turns it invisible there.
     */
    private fun faceName(face: BlockFace): Component = Component.translatable(
        "menu.smartstorage.side",
        Component.translatable("menu.smartstorage.face.${face.name.lowercase()}")
    )

    private fun directions(key: String, type: NetworkConnectionType): Component = Component.translatable(
        key,
        NamedTextColor.GRAY,
        Component.translatable(
            if (type.insert) "menu.smartstorage.port.on" else "menu.smartstorage.port.off",
            if (type.insert) NamedTextColor.GREEN else NamedTextColor.RED
        ),
        Component.translatable(
            if (type.extract) "menu.smartstorage.port.on" else "menu.smartstorage.port.off",
            if (type.extract) NamedTextColor.GREEN else NamedTextColor.RED
        )
    ).withoutPreFormatting()

    /**
     * Everything one side of the interface does: two directions per network, the filters that gate them,
     * and which of Nova's fluids this side deals in.
     */
    private inner class PortMenu(private val face: BlockFace) {

        private val statusItem = ClickableItem({ faceIcon(face) })

        private val insertItem = ClickableItem(
            { toggleIcon(itemConfig().insert, DefaultGuiItems.BLUE_BTN, "menu.smartstorage.port.insert") },
            { _, _, _ -> toggle(itemHolder, ITEM, insert = true) }
        )
        private val extractItem = ClickableItem(
            { extractIcon() },
            { _, _, _ -> toggle(itemHolder, ITEM, insert = false) }
        )

        private val fluidInsertItem = ClickableItem(
            { toggleIcon(fluidConfig().insert, DefaultGuiItems.BLUE_BTN, "menu.smartstorage.port.fluid_insert") },
            { _, _, _ -> toggle(fluidHolder, FLUID, insert = true) }
        )
        private val fluidExtractItem = ClickableItem(
            { fluidExtractIcon() },
            { _, _, _ -> toggle(fluidHolder, FLUID, insert = false) }
        )

        private val fluidKindItem = ClickableItem({ fluidKindIcon() }, { _, _, _ -> cycleFluid() })

        private val priorityItem = ClickableItem({ priorityIcon(priorityOf(face)) })

        /**
         * The slots in front of Nova's own per-face filter maps. Seeded from them, because those are
         * where a filter actually lives — this inventory is only how a player reaches one.
         */
        private val insertFilterInventory = filterInventory(extract = false)
        private val extractFilterInventory = filterInventory(extract = true)

        /**
         * Items on the top row with their filters under them, fluids on the bottom. The side's summary
         * sits at the top, clear of the fluid it names.
         */
        private val gui = Gui.builder()
            .setStructure(
                ". . n x . i . . .",
                ". . a b . . m v p",
                ". . f g . k . . ."
            )
            .addIngredient('n', insertItem)
            .addIngredient('x', extractItem)
            .addIngredient('a', insertFilterInventory, GuiItems.INSERT_FILTER_PLACEHOLDER)
            .addIngredient('b', extractFilterInventory, GuiItems.EXTRACT_FILTER_PLACEHOLDER)
            .addIngredient('f', fluidInsertItem)
            .addIngredient('g', fluidExtractItem)
            .addIngredient('i', statusItem)
            .addIngredient('k', fluidKindItem)
            .addIngredient('v', priorityItem)
            .addIngredient('m', RemoveNumberItem({ PRIORITY_RANGE }, { priorityOf(face) }, ::setPriority, "menu.smartstorage.priority_down"))
            .addIngredient('p', AddNumberItem({ PRIORITY_RANGE }, { priorityOf(face) }, ::setPriority, "menu.smartstorage.priority_up"))
            .build()

        fun open(player: Player) {
            val window = Window.builder()
                .setTitle(GuiTextures.INTERFACE_SIDE.getTitle(faceName(face)))
                .setUpperGui(gui)
                .build(player)

            menuContainer.registerWindow(window)
            window.open()
        }

        fun update() {
            statusItem.notifyWindows()
            insertItem.notifyWindows()
            extractItem.notifyWindows()
            fluidInsertItem.notifyWindows()
            fluidExtractItem.notifyWindows()
            fluidKindItem.notifyWindows()
            priorityItem.notifyWindows()
        }

        /**
         * Sets all four of Nova's per-face priorities together — insert and extract, items and fluids.
         * They are one number in the menu, so they have to be one number underneath, or a player who
         * only ever sees the item insert value could be ordered by an extract value they never touched.
         */
        private fun setPriority(value: Int) {
            NetworkManager.queueWrite(pos.chunkPos) { state ->
                itemHolder.insertPriorities[face] = value
                itemHolder.extractPriorities[face] = value
                fluidHolder.insertPriorities[face] = value
                fluidHolder.extractPriorities[face] = value

                state.getNetwork(this@StorageInterface, ITEM, face)?.markDirty()
                state.getNetwork(this@StorageInterface, FLUID, face)?.markDirty()

                runTask {
                    update()
                    menuContainer.forEachMenu(StorageInterfaceMenu::update)
                }
            }
        }

        private fun itemConfig(): NetworkConnectionType =
            itemHolder.connectionConfig[face] ?: NetworkConnectionType.NONE

        private fun fluidConfig(): NetworkConnectionType =
            fluidHolder.connectionConfig[face] ?: NetworkConnectionType.NONE

        private fun faceFluid(): FluidType? =
            (fluidHolder.containerConfig[face] as? NetworkFluidView)?.fluid

        //<editor-fold desc="writes", defaultstate="collapsed">

        /**
         * Flips one direction of one network for this face.
         *
         * The write has to go through [NetworkManager], on its thread, and be followed by the two calls
         * that tell Nova the topology may have moved — that is the same protocol its own side config
         * menu uses, and skipping either half leaves the network still routing through a side the player
         * has just closed.
         *
         * Everything off on both networks means no port at all, so the model, the hitboxes and the block
         * state behind them have to follow: [applyConnections] is what knows how to do all three, and it
         * is called here rather than left to a network update because closing a side does not always
         * produce one. It also re-runs [enforceExtractFilters], which is why asking for extraction
         * without a filter simply does not take.
         */
        private fun toggle(holder: ContainerEndPointDataHolder<*>, networkType: NetworkType<*>, insert: Boolean) {
            NetworkManager.queueWrite(pos.chunkPos) { state ->
                val current = holder.connectionConfig[face] ?: NetworkConnectionType.NONE
                holder.connectionConfig[face] = if (insert)
                    NetworkConnectionType.of(!current.insert, current.extract)
                else
                    NetworkConnectionType.of(current.insert, !current.extract)

                state.getNetwork(this@StorageInterface, networkType, face)?.markDirty()
                state.handleEndPointAllowedFacesChange(this@StorageInterface, networkType, face)

                applyConnections(state)
                runTask(::update)
            }
        }

        /**
         * Steps this face on to the next fluid. With two of them that is a switch; written as a cycle
         * because Nova's fluid list is what decides how many there are.
         */
        private fun cycleFluid() {
            NetworkManager.queueWrite(pos.chunkPos) { state ->
                val current = fluidHolder.containerConfig[face]
                val index = fluidViews.indexOfFirst { it === current }
                fluidHolder.containerConfig[face] = fluidViews[(index + 1).mod(fluidViews.size)]

                state.getNetwork(this@StorageInterface, FLUID, face)?.markDirty()
                state.handleEndPointAllowedFacesChange(this@StorageInterface, FLUID, face)

                applyConnections(state)
                runTask(::update)
            }
        }

        private fun filterInventory(extract: Boolean): VirtualInventory {
            val inventory = VirtualInventory(null, 1, arrayOfNulls(1), intArrayOf(1))

            val filters = if (extract) itemHolder.extractFilters else itemHolder.insertFilters
            filters[face]?.let { inventory.setItem(SELF_UPDATE_REASON, 0, it.toItemStack()) }

            inventory.addPreUpdateHandler { event ->
                if (event.updateReason == SELF_UPDATE_REASON)
                    return@addPreUpdateHandler

                val newItem = event.newItem
                if (newItem != null && !newItem.isItemFilter()) {
                    event.isCancelled = true
                    return@addPreUpdateHandler
                }

                applyFilter(extract, newItem?.getItemFilter())
            }

            return inventory
        }

        private fun applyFilter(extract: Boolean, filter: ItemFilter<*>?) {
            NetworkManager.queueWrite(pos.chunkPos) { state ->
                val filters = if (extract) itemHolder.extractFilters else itemHolder.insertFilters
                if (filter == null) filters.remove(face) else filters[face] = filter

                // taking an extract filter out closes the side, on both networks
                enforceExtractFilters()

                state.getNetwork(this@StorageInterface, ITEM, face)?.markDirty()
                state.handleEndPointAllowedFacesChange(this@StorageInterface, ITEM, face)
                state.getNetwork(this@StorageInterface, FLUID, face)?.markDirty()
                state.handleEndPointAllowedFacesChange(this@StorageInterface, FLUID, face)

                applyConnections(state)
                runTask(::update)
            }
        }

        //</editor-fold>

        //<editor-fold desc="icons", defaultstate="collapsed">

        /**
         * Nova's own side config colours, because these switches mean exactly what its do: blue for what
         * goes in, orange for what comes out, grey for a direction that is closed.
         */
        private fun toggleIcon(on: Boolean, onItem: NovaItem, key: String): ItemBuilder =
            (if (on) onItem else DefaultGuiItems.GRAY_BTN).createClientsideItemBuilder().setName(
                Component.translatable(
                    key,
                    if (on) NamedTextColor.GREEN else NamedTextColor.GRAY,
                    Component.translatable(if (on) "menu.smartstorage.port.on" else "menu.smartstorage.port.off")
                ).withoutPreFormatting()
            )

        /**
         * The one switch that can refuse to move, so it is the one that has to say why.
         */
        private fun extractIcon(): ItemBuilder {
            val builder = toggleIcon(itemConfig().extract, DefaultGuiItems.ORANGE_BTN, "menu.smartstorage.port.extract")
            if (itemHolder.extractFilters[face] == null) {
                builder.addLoreLines(
                    Component.translatable("menu.smartstorage.port.needs_filter", NamedTextColor.RED)
                        .withoutPreFormatting()
                )
            }
            return builder
        }

        /**
         * Unlike its item counterpart this switch always moves. The filter slots are about items and
         * nothing else, so what governs a fluid side is the picker beside it and these two switches.
         */
        private fun fluidExtractIcon(): ItemBuilder =
            toggleIcon(fluidConfig().extract, DefaultGuiItems.ORANGE_BTN, "menu.smartstorage.port.fluid_extract")

        /**
         * An empty bucket while the side moves no fluid at all: the picker still works — it is how you
         * choose before opening a direction — but showing a full one would claim something is flowing.
         */
        private fun fluidKindIcon(): ItemBuilder {
            val fluid = faceFluid()
            // the name still says which fluid the side is set to; only the bucket empties, because a full
            // one would claim something is flowing through a side that moves nothing
            val flowing = fluid != null && fluidConfig() != NetworkConnectionType.NONE

            return (if (flowing) ItemBuilder(fluid.bucket) else ItemBuilder(Material.BUCKET))
                .setName(
                    Component.translatable(
                        "menu.smartstorage.port.fluid_kind",
                        NamedTextColor.GRAY,
                        Component.translatable(fluid?.localizedName ?: "menu.smartstorage.port.off", NamedTextColor.GREEN)
                    ).withoutPreFormatting()
                )
                .addLoreLines(
                    Component.translatable("menu.smartstorage.port.fluid_kind.hint", NamedTextColor.DARK_GRAY)
                        .withoutPreFormatting()
                )
        }

        //</editor-fold>

    }

    /**
     * The block's own menu is a summary of its six sides, exactly as the storage connector's is: there is
     * nothing left that belongs to the block rather than to one of its faces.
     */
    @TileEntityMenuClass
    inner class StorageInterfaceMenu : GlobalTileEntityMenu(GuiTextures.STORAGE_INTERFACE) {

        private val statusItem = ClickableItem({ statusIcon() })

        private val networkItem = ClickableItem({ networkStatusIcon(storageNetwork) })

        private val faceItems = CUBE_FACES.map { face ->
            ClickableItem({ faceIcon(face) }, { _, player, _ -> portMenu(face).open(player) })
        }

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . u",
                ". i n 1 2 3 4 5 6",
                ". . . . . . . . ."
            )
            .addIngredient('u', OpenUpgradesItem(upgradeHolder))
            .addIngredient('i', statusItem)
            .addIngredient('n', networkItem)
            .addIngredient('1', faceItems[0])
            .addIngredient('2', faceItems[1])
            .addIngredient('3', faceItems[2])
            .addIngredient('4', faceItems[3])
            .addIngredient('5', faceItems[4])
            .addIngredient('6', faceItems[5])
            .build()

        fun update() {
            statusItem.notifyWindows()
            networkItem.notifyWindows()
            faceItems.forEach(ClickableItem::notifyWindows)
        }

        private fun statusIcon(): ItemBuilder {
            val served = servedFaces.size

            val builder = ItemBuilder(Material.HOPPER)
            builder.setName(Component.translatable("menu.smartstorage.interface.title").withoutPreFormatting())
            builder.setLore(
                listOf(
                    if (served > 0)
                        Component.translatable(
                            "menu.smartstorage.interface.attached",
                            NamedTextColor.GREEN,
                            Component.text(served, NamedTextColor.GREEN)
                        ).withoutPreFormatting()
                    else
                        Component.translatable("menu.smartstorage.interface.detached", NamedTextColor.RED)
                            .withoutPreFormatting(),
                    // the two figures a Speed Upgrade moves, where a player can see what one bought
                    Component.translatable(
                        "menu.smartstorage.interface.item_rate",
                        NamedTextColor.GRAY,
                        Component.text(itemsPerTick(), NamedTextColor.GREEN)
                    ).withoutPreFormatting(),
                    Component.translatable(
                        "menu.smartstorage.interface.fluid_rate",
                        NamedTextColor.GRAY,
                        Component.text(NumberFormatUtils.getFluidString(fluidPerTick()), NamedTextColor.GREEN)
                    ).withoutPreFormatting()
                )
            )
            return builder
        }

    }

}
