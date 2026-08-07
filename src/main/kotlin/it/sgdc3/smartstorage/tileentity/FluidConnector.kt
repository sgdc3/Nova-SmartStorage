package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.networkStatusIcon
import it.sgdc3.smartstorage.gui.priorityIcon
import it.sgdc3.smartstorage.network.DEFAULT_PRIORITY
import it.sgdc3.smartstorage.network.FluidProvider
import it.sgdc3.smartstorage.network.PRIORITY_RANGE
import it.sgdc3.smartstorage.network.StorageEndPoint
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
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
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.window.Window
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.ui.menu.addIngredient
import xyz.xenondevs.nova.ui.menu.item.AddNumberItem
import xyz.xenondevs.nova.ui.menu.item.RemoveNumberItem
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.NumberFormatUtils
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.container.NetworkedFluidContainer
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.holder.FluidHolder
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.item.DefaultGuiItems
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * Turns every tank it touches into fluid storage for the network, exactly as a [StorageConnector] does
 * for chests.
 *
 * The two used to be one block. Splitting them is worth the extra recipe because the merged version made
 * every side ask two unrelated questions — is there a chest here, is there a tank here — and answer both
 * in one menu, when in practice a side has one thing against it. A player wiring up a tank farm now
 * reaches for the block that is about tanks, and its menu has nothing in it that is not.
 *
 * It is a hub like the item connector: all six sides live at once, each with its own priority and its
 * own direction switches, so one block wedged between two tanks serves both.
 *
 * There is no filter slot, and that is not an omission. A filter is a list of the many items that may
 * pass; Nova has exactly two fluids, and which of them a side deals in is not a question a filter is
 * shaped to answer — the tank on that side already decides it.
 */
class FluidConnector(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : StorageHub(pos, state, data), StorageEndPoint {

    override val portModel = Models.FLUID_CONNECTOR_ATTACHMENT
    override val portModelOff = Models.FLUID_CONNECTOR_ATTACHMENT_OFF

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    /**
     * One per side, whether or not anything is mounted there, so a side keeps its settings while the
     * tank is temporarily gone.
     */
    private val ports: Map<BlockFace, TankPort> =
        CUBE_FACES.associateWithTo(enumMap()) { TankPort(it) }

    @Volatile
    override var fluidProviders: List<FluidProvider> = emptyList()
        private set

    init {
        holders += storageHolder
    }

    override fun handleEnable() {
        super.handleEnable()
        refreshTanks()
    }

    override fun handleTick() {
        // cheap enough to redo every tick, and it means a tank that was broken or replaced is picked up
        // within 50 ms
        refreshTanks()

        if (setPowered(storageNetwork?.isOnline == true))
            menuContainer.forEachMenu(FluidConnectorMenu::update)
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
        fluidProviders = emptyList()
        for (port in ports.values)
            port.mount(null)
    }

    private fun refreshTanks() {
        val active = HashSet<BlockFace>()
        for (face in CUBE_FACES) {
            val port = ports.getValue(face)

            port.mount(resolveTank(pos.advance(face)))
            if (port.isActive)
                active += face
        }

        fluidProviders = active.map(ports::getValue)

        if (setPortFaces(active))
            menuContainer.forEachMenu(FluidConnectorMenu::update)
    }

    /**
     * A tank, of any kind Nova recognises as one.
     *
     * Much shorter than the item connector's equivalent, and for a reason worth stating: there is no
     * vanilla half to this. A cauldron is not a tank in any sense the network could use — three levels
     * of water, no type it will report, and no way to put lava in it — so a side is a Nova end point
     * with a tank on it, or it is nothing.
     */
    private fun resolveTank(neighbour: BlockPos): FluidBacking? {
        // Nova's own lookup throws for a chunk it has not loaded, and a hub on a chunk border has
        // neighbours in the next one. Asking Bukkit whether the chunk is loaded neither loads it nor
        // builds anything.
        if (!pos.world.isChunkLoaded(neighbour.x shr 4, neighbour.z shr 4))
            return null

        val tileEntity: TileEntity? = WorldDataManager.getTileEntity(neighbour)
        // one of ours would mean the network swallowing itself
        if (tileEntity == null || tileEntity is StorageEndPoint || tileEntity !is NetworkEndPoint)
            return null

        val holder = tileEntity.holders.filterIsInstance<FluidHolder>().firstOrNull() ?: return null
        val container = holder.containers
            .entries.firstOrNull { (_, type) -> type == NetworkConnectionType.BUFFER }
            ?.key
            ?: return null

        return FluidBacking(container)
    }

    override fun openPortMenu(player: Player, face: BlockFace) {
        ports.getValue(face).openMenu(player)
    }

    override suspend fun handleNetworkLoaded(state: NetworkState) = applyArms(state)

    override suspend fun handleNetworkUpdate(state: NetworkState) = applyArms(state)

    private suspend fun applyArms(state: NetworkState) {
        val connected = state.getConnectedNodes(this).row(NetworkTypes.STORAGE).keys.toSet()

        // no storage connection left means no network rebuilt us — see StorageNetworkGroup.lastTick
        if (connected.isEmpty())
            storageNetwork = null

        runTask {
            if (isEnabled)
                setArmFaces(connected)
        }
    }

    /**
     * A tank, seen through Nova's own [NetworkedFluidContainer].
     *
     * Everything here is a field read on the container: a fluid container holds *one* type at a time and
     * reports it, so there is no contents array to walk and no snapshot to keep. The reference is
     * resolved once on the server thread and used from the network's, which is the same trade Nova makes
     * to drive these from its own ticker.
     */
    internal class FluidBacking(private val container: NetworkedFluidContainer) {

        val identity: Any get() = container
        val usedAmount: Long get() = container.amount
        val totalAmount: Long get() = container.capacity

        fun amountOf(type: FluidType): Long =
            if (container.type == type) container.amount else 0L

        fun collectInto(index: MutableMap<FluidType, Long>) {
            val type = container.type ?: return
            val amount = container.amount
            if (amount > 0L)
                index.merge(type, amount) { a, b -> a + b }
        }

        fun insert(type: FluidType, amount: Long): Long {
            if (!container.accepts(type))
                return 0L

            return container.addFluid(type, amount)
        }

        fun extract(type: FluidType, amount: Long): Long {
            // takeFluid has no type parameter: it takes whatever is in there, so the check has to happen
            // here or a request for water would come back with lava
            if (container.type != type)
                return 0L

            return container.takeFluid(amount)
        }

    }

    /**
     * One side of the connector, and everything the network knows about the tank mounted there.
     */
    inner class TankPort(private val face: BlockFace) : FluidProvider {

        @Volatile
        private var backing: FluidBacking? = null

        /**
         * Set by the outer class as it scans, which Kotlin permits straight into a private of an inner
         * class — and is better than opening it up, since [FluidBacking] is nobody else's business.
         */
        internal fun mount(backing: FluidBacking?) {
            this.backing = backing
        }

        private val priorityValue = storedValue("priority_${face.name}") { DEFAULT_PRIORITY }
        override var priority: Int by priorityValue

        private val insertValue = storedValue("insert_${face.name}") { true }
        var allowInsert: Boolean by insertValue

        private val extractValue = storedValue("extract_${face.name}") { true }
        var allowExtract: Boolean by extractValue

        /**
         * A side with both directions off is not storage at all: no port is drawn and the tank is left
         * out of the network.
         */
        val isActive: Boolean
            get() = backing != null && (allowInsert || allowExtract)

        private var menu: PortMenu? = null

        init {
            if (priority !in PRIORITY_RANGE)
                priority = priority.coerceIn(PRIORITY_RANGE)
        }

        fun openMenu(player: Player) {
            val menu = this.menu ?: PortMenu().also { this.menu = it }
            menu.open(player)
        }

        override val storageIdentity: Any
            get() = backing?.identity ?: this

        override val usedAmount: Long
            get() = backing?.usedAmount ?: 0L

        override val totalAmount: Long
            get() = backing?.totalAmount ?: 0L

        override val hasFluidRoom: Boolean
            get() = allowInsert && backing?.let { it.usedAmount < it.totalAmount } == true

        override fun collectFluidsInto(index: MutableMap<FluidType, Long>) {
            backing?.collectInto(index)
        }

        override fun amountOf(type: FluidType): Long = backing?.amountOf(type) ?: 0L

        /**
         * Nothing, once this side has been told to keep what it holds. [amountOf] still answers with
         * everything, because a terminal showing what is in a tank is right even when the network may
         * not take it out — but a promise has to be one this side will keep, and a side with extraction
         * off keeps none. See [it.sgdc3.smartstorage.network.StorageProvider.extractableCountOf].
         */
        override fun extractableAmountOf(type: FluidType): Long =
            if (allowExtract) amountOf(type) else 0L

        override fun insertFluid(type: FluidType, amount: Long): Long {
            if (!allowInsert)
                return 0L

            return backing?.insert(type, amount) ?: 0L
        }

        override fun extractFluid(type: FluidType, amount: Long): Long {
            if (!allowExtract)
                return 0L

            return backing?.extract(type, amount) ?: 0L
        }

        /**
         * Left unstyled on purpose: it is the window title as well as an item name, and a window title
         * is drawn dark on the panel.
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
                    isActive -> Material.WATER_BUCKET
                    backing != null -> Material.BARRIER
                    else -> Material.GRAY_STAINED_GLASS_PANE
                }
            )
            builder.setName(faceName().withoutPreFormatting())

            val lore = ArrayList<Component>()
            lore += when {
                backing == null ->
                    Component.translatable("menu.smartstorage.fluid_connector.empty", NamedTextColor.RED)
                        .withoutPreFormatting()

                !isActive ->
                    Component.translatable("menu.smartstorage.port.disabled", NamedTextColor.RED)
                        .withoutPreFormatting()

                else -> Component.translatable(
                    "menu.smartstorage.fluid_connector.stored",
                    NamedTextColor.GRAY,
                    Component.text(NumberFormatUtils.getFluidString(usedAmount, totalAmount), NamedTextColor.GREEN)
                ).withoutPreFormatting()
            }

            lore += Component.translatable(
                "menu.smartstorage.port.fluid_directions",
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

        /**
         * Configures this side alone: two directions and a priority, and nothing else, because there is
         * nothing else a tank side has.
         */
        private inner class PortMenu {

            private val statusItem = ClickableItem({ icon() })
            private val insertItem = ClickableItem(
                { toggleIcon(allowInsert, DefaultGuiItems.BLUE_BTN, "menu.smartstorage.port.fluid_insert") },
                { _, _, _ -> toggle { allowInsert = !allowInsert } }
            )
            private val extractItem = ClickableItem(
                { toggleIcon(allowExtract, DefaultGuiItems.ORANGE_BTN, "menu.smartstorage.port.fluid_extract") },
                { _, _, _ -> toggle { allowExtract = !allowExtract } }
            )

            private val priorityItem = ClickableItem({ priorityIcon(priority) })

            private val gui = Gui.builder()
                .setStructure(
                    ". . . . . . . . .",
                    ". . . n x . m v p",
                    ". i . . . . . . ."
                )
                .addIngredient('n', insertItem)
                .addIngredient('x', extractItem)
                .addIngredient('i', statusItem)
                .addIngredient('v', priorityItem)
                .addIngredient('m', RemoveNumberItem({ PRIORITY_RANGE }, { priority }, ::setPriority, "menu.smartstorage.priority_down"))
                .addIngredient('p', AddNumberItem({ PRIORITY_RANGE }, { priority }, ::setPriority, "menu.smartstorage.priority_up"))
                .build()

            fun open(player: Player) {
                val window = Window.builder()
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
             * hitboxes and the block state — [refreshTanks] is what knows how to do all three.
             */
            private inline fun toggle(change: () -> Unit) {
                change()
                refreshTanks()
                statusItem.notifyWindows()
                insertItem.notifyWindows()
                extractItem.notifyWindows()
            }

            private fun toggleIcon(on: Boolean, onItem: NovaItem, key: String): ItemBuilder =
                (if (on) onItem else DefaultGuiItems.GRAY_BTN).createClientsideItemBuilder().setName(
                    Component.translatable(
                        key,
                        if (on) NamedTextColor.GREEN else NamedTextColor.GRAY,
                        Component.translatable(if (on) "menu.smartstorage.port.on" else "menu.smartstorage.port.off")
                    ).withoutPreFormatting()
                )

        }

    }

    @TileEntityMenuClass
    inner class FluidConnectorMenu : GlobalTileEntityMenu(GuiTextures.STORAGE_CONNECTOR) {

        private val statusItem = ClickableItem({ statusIcon() })
        private val networkItem = ClickableItem({ networkStatusIcon(storageNetwork) })

        private val faceItems = CUBE_FACES.map { face ->
            val port = ports.getValue(face)
            ClickableItem({ port.icon() }, { _, player, _ -> port.openMenu(player) })
        }

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . .",
                "n i . 1 2 3 4 5 6",
                ". . . . . . . . ."
            )
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
            val builder = ItemBuilder(Material.WATER_BUCKET)
            builder.setName(Component.translatable("menu.smartstorage.fluid_connector.title").withoutPreFormatting())

            val mounted = fluidProviders.size
            builder.setLore(
                listOf(
                    if (mounted > 0)
                        Component.translatable(
                            "menu.smartstorage.fluid_connector.attached",
                            NamedTextColor.GREEN,
                            Component.text(mounted, NamedTextColor.GREEN)
                        ).withoutPreFormatting()
                    else
                        Component.translatable("menu.smartstorage.fluid_connector.detached", NamedTextColor.RED)
                            .withoutPreFormatting()
                )
            )
            return builder
        }

    }

}
