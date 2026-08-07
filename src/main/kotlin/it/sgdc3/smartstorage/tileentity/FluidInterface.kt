package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.networkStatusIcon
import it.sgdc3.smartstorage.gui.priorityIcon
import it.sgdc3.smartstorage.network.DEFAULT_PRIORITY
import it.sgdc3.smartstorage.network.FluidGateway
import it.sgdc3.smartstorage.network.NetworkFluidView
import it.sgdc3.smartstorage.network.PRIORITY_RANGE
import it.sgdc3.smartstorage.network.StorageEndPoint
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.network.TransferBudget
import it.sgdc3.smartstorage.registry.Blocks.FLUID_INTERFACE
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.Models
import it.sgdc3.smartstorage.registry.NetworkTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.collections.enumMap
import xyz.xenondevs.commons.collections.toEnumSet
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.window.Window
import xyz.xenondevs.nova.addon.simpleupgrades.gui.OpenUpgradesItem
import xyz.xenondevs.nova.addon.simpleupgrades.registry.UpgradeTypes
import xyz.xenondevs.nova.addon.simpleupgrades.storedUpgradeHolder
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.ui.menu.item.AddNumberItem
import xyz.xenondevs.nova.ui.menu.item.RemoveNumberItem
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.NumberFormatUtils
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes.FLUID
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.holder.FluidHolder
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.item.DefaultGuiItems
import xyz.xenondevs.nova.world.item.NovaItem
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

private const val NEIGHBOUR_RESCAN_TICKS = 20

private val BASE_TRANSFER by FLUID_INTERFACE.config.entry<Double>("base_transfer")

/**
 * Bridges a storage network's *fluids* into Nova's fluid network, as [StorageInterface] does for items.
 *
 * Sitting on both at once, it presents everything the system holds as a pair of tanks. Run Logistics
 * fluid pipes into it, or set it straight against a machine, and the network fills or drains it.
 *
 * ## Everything is per face
 *
 * A side has its own two directions, its own fluid and its own priority. An interface between a furnace
 * and a pipe is doing two unrelated jobs, and one setting could only describe one of them.
 *
 * ## Which fluid, and no filter
 *
 * Each side deals in one of Nova's two fluids, chosen with the picker in its menu — see [NetworkFluidView]
 * for why a view is per fluid rather than per block. There is deliberately no filter slot: a filter is a
 * list of the many items that may pass, and "which of two fluids" is not a question shaped like that.
 *
 * Extraction still starts closed on every side, so nothing leaves before somebody opens it.
 *
 * ## It is slow on purpose
 *
 * Nova takes a network's throughput from its *cables* and puts no floor under it, so an interface set
 * straight against a tank belongs to a network with no cable and no limit — and fluid networks tick every
 * tick. Before it had a rate of its own, the whole system emptied into that tank instantly. See
 * [TransferBudget]; Speed Upgrades raise it.
 */
class FluidInterface(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : StorageHub(pos, state, data), StorageEndPoint, FluidGateway {

    override val portModel = Models.FLUID_INTERFACE_ATTACHMENT
    override val portModelOff = Models.FLUID_INTERFACE_ATTACHMENT_OFF

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    private val upgradeHolder = storedUpgradeHolder(UpgradeTypes.SPEED)

    private val input = TransferBudget()
    private val output = TransferBudget()

    /**
     * One tank per fluid Nova has — see [NetworkFluidView] for why it cannot be one tank for both.
     *
     * The UUIDs are derived from this block's own so they stay put across restarts: Nova stores a face's
     * chosen container by UUID, and a random one per load would scramble every side.
     */
    private val fluidViews: List<NetworkFluidView> = FluidType.entries.map { fluid ->
        NetworkFluidView(
            this,
            UUID.nameUUIDFromBytes("$uuid:${fluid.name}".toByteArray()),
            fluid,
            input,
            output
        )
    }

    /**
     * Inputs open, outputs closed. Nothing leaves a side until somebody opens it.
     */
    private val fluidHolder = storedFluidHolder(
        fluidViews.first() to NetworkConnectionType.BUFFER,
        *fluidViews.drop(1).map { it to NetworkConnectionType.BUFFER }.toTypedArray(),
        defaultConnectionConfig = { CUBE_FACES.associateWithTo(enumMap()) { NetworkConnectionType.INSERT } }
    )

    private val portMenus = enumMap<BlockFace, PortMenu>()

    @Volatile
    private var attachedFaces: Set<BlockFace> = emptySet()

    @Volatile
    private var servedFaces: Set<BlockFace> = emptySet()

    init {
        holders += storageHolder
    }

    override fun handleEnable() {
        super.handleEnable()
        // see StorageInterface.syncAllowedFaces: a closed face Nova still holds a connection to makes its
        // network builder throw, and that takes every other network down with it
        NetworkManager.queueWrite(pos.chunkPos, ::syncAllowedFaces)
    }

    private suspend fun syncAllowedFaces(state: NetworkState) {
        for (face in CUBE_FACES) {
            if (fluidHolder.connectionConfig[face] == NetworkConnectionType.NONE)
                state.handleEndPointAllowedFacesChange(this, FLUID, face)
        }
    }

    override suspend fun handleNetworkLoaded(state: NetworkState) = applyConnections(state)

    override suspend fun handleNetworkUpdate(state: NetworkState) = applyConnections(state)

    override fun handleTick() {
        refillBudgets()

        if (setPowered(storageNetwork?.isOnline == true))
            menuContainer.forEachMenu(FluidInterfaceMenu::update)

        if (serverTick % max(1, NEIGHBOUR_RESCAN_TICKS) != 0)
            return

        NetworkManager.queueRead(pos.chunkPos) { state ->
            val attached = neighbours(state)
            if (attached == attachedFaces)
                return@queueRead

            attachedFaces = attached
            runTask {
                menuContainer.forEachMenu(FluidInterfaceMenu::update)
                portMenus.values.forEach(PortMenu::update)
            }
        }
    }

    /**
     * How much this interface moves per network tick at its current speed.
     */
    private fun perTick(): Long =
        (BASE_TRANSFER * upgradeHolder.getValue(UpgradeTypes.SPEED)).roundToLong().coerceAtLeast(0L)

    /**
     * Set rather than accumulated: an interface nobody used for a minute has not banked a minute's worth
     * of throughput, or the first thing to touch it would empty the system after all.
     */
    private fun refillBudgets() {
        val rate = perTick()
        input.refill(rate)
        output.refill(rate)
    }

    private suspend fun applyConnections(state: NetworkState) {
        val connected = state.getConnectedNodes(this)
        val arms = connected.row(NetworkTypes.STORAGE).keys.toEnumSet()

        val ports = HashSet<BlockFace>()
        for ((face, node) in connected.row(FLUID)) {
            if (node !is NetworkEndPoint)
                continue

            val type = fluidHolder.connectionConfig[face] ?: continue
            if (type.insert || type.extract)
                ports += face
        }

        attachedFaces = neighbours(state)
        servedFaces = ports

        if (arms.isEmpty())
            storageNetwork = null

        runTask {
            if (!isEnabled)
                return@runTask

            setArmFaces(arms)
            setPortFaces(ports)
            menuContainer.forEachMenu(FluidInterfaceMenu::update)
        }
    }

    /**
     * Sides with something against them this interface *could* serve, read from the node map rather than
     * from the connected ones — see [StorageInterface.neighbours] for why that distinction matters.
     */
    private fun neighbours(state: NetworkState): Set<BlockFace> =
        state.getNearbyNodes(pos, CUBE_FACES)
            .filterValues { node -> node is NetworkEndPoint && node.holders.any { it is FluidHolder } }
            .keys
            .toEnumSet()

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

    private fun priorityOf(face: BlockFace): Int =
        fluidHolder.insertPriorities[face] ?: DEFAULT_PRIORITY

    private fun faceName(face: BlockFace): Component = Component.translatable(
        "menu.smartstorage.side",
        Component.translatable("menu.smartstorage.face.${face.name.lowercase()}")
    )

    /**
     * What a side is doing, shown both as its own menu's summary and as its entry in the block's menu.
     */
    private fun faceIcon(face: BlockFace): ItemBuilder {
        val config = fluidHolder.connectionConfig[face] ?: NetworkConnectionType.NONE
        val open = config != NetworkConnectionType.NONE
        val attached = face in attachedFaces

        val builder = ItemBuilder(
            when {
                attached && open -> Material.WATER_BUCKET
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

            else -> Component.translatable(
                "menu.smartstorage.port.fluid_directions",
                NamedTextColor.GRAY,
                Component.translatable(
                    if (config.insert) "menu.smartstorage.port.on" else "menu.smartstorage.port.off",
                    if (config.insert) NamedTextColor.GREEN else NamedTextColor.RED
                ),
                Component.translatable(
                    if (config.extract) "menu.smartstorage.port.on" else "menu.smartstorage.port.off",
                    if (config.extract) NamedTextColor.GREEN else NamedTextColor.RED
                )
            ).withoutPreFormatting()
        }

        faceFluid(face)?.let { fluid ->
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

    private fun faceFluid(face: BlockFace): FluidType? =
        (fluidHolder.containerConfig[face] as? NetworkFluidView)?.fluid

    /**
     * Everything one side does: two directions, which fluid, and a priority.
     */
    private inner class PortMenu(private val face: BlockFace) {

        private val statusItem = ClickableItem({ faceIcon(face) })

        private val insertItem = ClickableItem(
            { toggleIcon(config().insert, DefaultGuiItems.BLUE_BTN, "menu.smartstorage.port.fluid_insert") },
            { _, _, _ -> toggle(insert = true) }
        )
        private val extractItem = ClickableItem(
            { toggleIcon(config().extract, DefaultGuiItems.ORANGE_BTN, "menu.smartstorage.port.fluid_extract") },
            { _, _, _ -> toggle(insert = false) }
        )

        private val fluidKindItem = ClickableItem({ fluidKindIcon() }, { _, _, _ -> cycleFluid() })

        private val priorityItem = ClickableItem({ priorityIcon(priorityOf(face)) })

        private val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . .",
                ". . . n x . m v p",
                ". i . k . . . . ."
            )
            .addIngredient('n', insertItem)
            .addIngredient('x', extractItem)
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
            fluidKindItem.notifyWindows()
            priorityItem.notifyWindows()
        }

        private fun config(): NetworkConnectionType =
            fluidHolder.connectionConfig[face] ?: NetworkConnectionType.NONE

        /**
         * Both of Nova's per-face priorities together, because the menu shows one number and a player
         * who only ever sees one must not be ordered by another they never touched.
         */
        private fun setPriority(value: Int) {
            NetworkManager.queueWrite(pos.chunkPos) { state ->
                fluidHolder.insertPriorities[face] = value
                fluidHolder.extractPriorities[face] = value

                state.getNetwork(this@FluidInterface, FLUID, face)?.markDirty()

                runTask {
                    update()
                    menuContainer.forEachMenu(FluidInterfaceMenu::update)
                }
            }
        }

        /**
         * Flips one direction for this face.
         *
         * The write goes through [NetworkManager] and is followed by the two calls that tell Nova the
         * topology may have moved — the same protocol its own side config uses. Skipping either half
         * leaves the network routing through a side the player has just closed, and a closed face Nova
         * still holds a connection to makes its builder throw.
         */
        private fun toggle(insert: Boolean) {
            NetworkManager.queueWrite(pos.chunkPos) { state ->
                val current = config()
                fluidHolder.connectionConfig[face] = if (insert)
                    NetworkConnectionType.of(!current.insert, current.extract)
                else
                    NetworkConnectionType.of(current.insert, !current.extract)

                state.getNetwork(this@FluidInterface, FLUID, face)?.markDirty()
                state.handleEndPointAllowedFacesChange(this@FluidInterface, FLUID, face)

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

                state.getNetwork(this@FluidInterface, FLUID, face)?.markDirty()
                state.handleEndPointAllowedFacesChange(this@FluidInterface, FLUID, face)

                applyConnections(state)
                runTask(::update)
            }
        }

        private fun toggleIcon(on: Boolean, onItem: NovaItem, key: String): ItemBuilder =
            (if (on) onItem else DefaultGuiItems.GRAY_BTN).createClientsideItemBuilder().setName(
                Component.translatable(
                    key,
                    if (on) NamedTextColor.GREEN else NamedTextColor.GRAY,
                    Component.translatable(if (on) "menu.smartstorage.port.on" else "menu.smartstorage.port.off")
                ).withoutPreFormatting()
            )

        /**
         * An empty bucket while the side moves nothing: the picker still works — it is how you choose
         * before opening a direction — but a full one would claim something is flowing.
         */
        private fun fluidKindIcon(): ItemBuilder {
            val fluid = faceFluid(face)
            val flowing = fluid != null && config() != NetworkConnectionType.NONE

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

    }

    @TileEntityMenuClass
    inner class FluidInterfaceMenu : GlobalTileEntityMenu(GuiTextures.STORAGE_INTERFACE) {

        private val statusItem = ClickableItem({ statusIcon() })
        private val networkItem = ClickableItem({ networkStatusIcon(storageNetwork) })

        private val faceItems = CUBE_FACES.map { face ->
            ClickableItem({ faceIcon(face) }, { _, player, _ -> portMenu(face).open(player) })
        }

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . u",
                "n i . 1 2 3 4 5 6",
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

            val builder = ItemBuilder(Material.WATER_BUCKET)
            builder.setName(Component.translatable("menu.smartstorage.fluid_interface.title").withoutPreFormatting())
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
                    Component.translatable(
                        "menu.smartstorage.interface.fluid_rate",
                        NamedTextColor.GRAY,
                        Component.text(NumberFormatUtils.getFluidString(perTick()), NamedTextColor.GREEN)
                    ).withoutPreFormatting()
                )
            )
            return builder
        }

    }

}
