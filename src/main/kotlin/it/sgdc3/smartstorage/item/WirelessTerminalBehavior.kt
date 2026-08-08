package it.sgdc3.smartstorage.item

import it.sgdc3.smartstorage.SmartStorage
import it.sgdc3.smartstorage.gui.CRAFTING_GRID_SIZE
import it.sgdc3.smartstorage.gui.CraftingGrid
import it.sgdc3.smartstorage.gui.TerminalContent
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.TERMINAL_REFRESH_TICKS
import it.sgdc3.smartstorage.tileentity.StorageController
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.dsl.anvilWindow
import xyz.xenondevs.invui.dsl.item
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.inventory.VirtualInventory
import xyz.xenondevs.invui.inventory.event.UpdateReason
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.window.Window
import xyz.xenondevs.nova.addon.simpleupgrades.registry.UpgradeTypes
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockInteract
import xyz.xenondevs.nova.context.intention.ItemUse
import xyz.xenondevs.nova.ui.overlay.guitexture.DefaultGuiTextures
import xyz.xenondevs.nova.ui.overlay.guitexture.GuiTexture
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.item.retrieveData
import xyz.xenondevs.nova.util.item.storeData
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.util.runTaskTimer
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.InteractionResult
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.item.behavior.ItemBehaviorFactory
import java.util.UUID
import kotlin.math.max

/**
 * A terminal you carry.
 *
 * Bound to one network by right-clicking its **controller**, and usable from anywhere within reach of an
 * access point on that same network. Those are two separate questions on purpose: the binding says
 * *which* system you are looking at, which never changes by walking around, and the range says whether
 * you can see it from here, which is the only thing a player is expected to think about.
 *
 * ## What it remembers
 *
 * The controller's position rather than its identity, because a position is something this addon can
 * turn back into a controller without keeping an index of every one on the server. The cost is that
 * moving a controller breaks the binding, which is a thing a player can see and fix by right-clicking
 * the new one.
 *
 * The range upgrades are a count on the item, and the slot that shows them is a view of that count. That
 * is what makes the upgrades survive closing the window: there is no inventory to lose, only a number
 * the slot is redrawn from.
 *
 * ## Why the upgrades have their own window
 *
 * Sneak and right-click for them, rather than a slot inside the terminal screen. A terminal that is out
 * of range does not open — so an upgrade slot living inside it could never be reached by the one player
 * who needs it, which is the player standing too far away.
 */
class WirelessTerminalBehavior(item: NovaItem) : ItemBehavior {

    private val baseRange: Double by item.config.entry<Double>("base_range")
    private val rangePerUpgrade: Double by item.config.entry<Double>("range_per_upgrade")
    private val maxUpgrades: Int by item.config.entry<Int>("max_range_upgrades")
    private val maxBulkCrafts: Int by item.config.entry<Int>("max_bulk_crafts")

    /**
     * Right-click in the air.
     */
    override fun use(itemStack: ItemStack, ctx: Context<ItemUse>): InteractionResult =
        handleUse(ctx[ItemUse.SOURCE_PLAYER], ctx[ItemUse.HELD_HAND], itemStack)

    /**
     * Right-click while pointing at a block, which is a different packet and therefore a different hook.
     * Both are here because a player pointing at the floor is not asking for something else to happen —
     * they are asking for the same thing, and an item that only worked aimed at the sky would read as
     * broken.
     *
     * Binding is unaffected. Vanilla offers a right-click to the *block* first and only falls through to
     * the item when the block passes, so a controller still takes the click and binds; and a sneaking
     * player skips the block entirely, which is exactly the case that should reach the upgrades.
     */
    override fun useOnBlock(itemStack: ItemStack, block: Block, ctx: Context<BlockInteract>): InteractionResult =
        handleUse(ctx[BlockInteract.SOURCE_PLAYER], ctx[BlockInteract.HELD_HAND], itemStack)

    private fun handleUse(player: Player?, hand: EquipmentSlot?, itemStack: ItemStack): InteractionResult {
        if (player == null || hand == null)
            return InteractionResult.Pass

        if (player.isSneaking) {
            openUpgrades(player, hand)
        } else {
            openTerminal(player, itemStack)
        }

        return InteractionResult.Success(swing = true)
    }

    //<editor-fold desc="binding", defaultstate="collapsed">

    /**
     * Points [itemStack] at [controller]. Called by the controller itself when it is right-clicked with
     * one of these, which is the only place a binding is ever made.
     */
    fun bind(itemStack: ItemStack, controller: StorageController) {
        val pos = controller.pos

        val compound = Compound()
        compound["world"] = pos.world.uid.toString()
        compound["x"] = pos.x
        compound["y"] = pos.y
        compound["z"] = pos.z

        itemStack.storeData(SmartStorage, BINDING_KEY, compound)
    }

    private fun binding(itemStack: ItemStack): BlockPos? {
        val compound = itemStack.retrieveData<Compound>(SmartStorage, BINDING_KEY) ?: return null

        val worldId: String = compound["world"] ?: return null
        val world = Bukkit.getWorld(UUID.fromString(worldId)) ?: return null
        val x: Int = compound["x"] ?: return null
        val y: Int = compound["y"] ?: return null
        val z: Int = compound["z"] ?: return null

        return BlockPos(world, x, y, z)
    }

    //</editor-fold>

    //<editor-fold desc="range", defaultstate="collapsed">

    private fun upgrades(itemStack: ItemStack): Int =
        (itemStack.retrieveData<Int>(SmartStorage, UPGRADES_KEY) ?: 0).coerceIn(0, maxUpgrades)

    private fun setUpgrades(itemStack: ItemStack, count: Int) {
        itemStack.storeData(SmartStorage, UPGRADES_KEY, count.coerceIn(0, maxUpgrades))
    }

    /**
     * How far this terminal can see, in blocks.
     *
     * All of the reach lives here rather than being split with the access point: the terminal is the
     * thing that gets carried away from the network and the thing that takes upgrades, so it is the
     * thing that should answer "can I see it from here". A point is only a place to be seen from.
     */
    private fun range(itemStack: ItemStack): Double =
        baseRange + upgrades(itemStack) * rangePerUpgrade

    /**
     * The network this terminal can see from where [player] is standing, or null with the reason already
     * shown to them.
     *
     * The two failures are told apart deliberately. "This is not bound to anything" and "you are too far
     * from an access point" are fixed by completely different actions, and a terminal that just refused
     * to open would leave the player guessing which.
     */
    private fun reachable(player: Player, itemStack: ItemStack): StorageNetwork? {
        val pos = binding(itemStack) ?: return fail(player, "menu.smartstorage.wireless.unbound")

        // Nova's lookup throws for a chunk it has not loaded, and a controller across the world usually
        // is one. Asking Bukkit neither loads it nor allocates.
        if (!pos.world.isChunkLoaded(pos.x shr 4, pos.z shr 4))
            return fail(player, "menu.smartstorage.wireless.out_of_reach")

        val controller = WorldDataManager.getTileEntity(pos) as? StorageController
            ?: return fail(player, "menu.smartstorage.wireless.no_controller")

        val network = controller.storageNetwork?.takeIf { it.isOnline }
            ?: return fail(player, "menu.smartstorage.wireless.offline")

        val reach = range(itemStack)
        val inReach = network.wirelessNodes().any { node ->
            val nodePos = node.pos
            if (nodePos.world != player.world)
                return@any false

            nodePos.location.add(0.5, 0.5, 0.5).distanceSquared(player.location) <= reach * reach
        }

        if (!inReach)
            return fail(player, "menu.smartstorage.wireless.out_of_reach")

        return network
    }

    private fun fail(player: Player, key: String): StorageNetwork? {
        player.sendActionBar(Component.translatable(key, NamedTextColor.RED))
        return null
    }

    //</editor-fold>

    //<editor-fold desc="windows", defaultstate="collapsed">

    private fun openTerminal(player: Player, itemStack: ItemStack) {
        val network = reachable(player, itemStack) ?: return
        Session(player, network).openList()
    }

    /**
     * One player's open terminal: the list, the crafting grid, and the search, all sharing one filter and
     * one view of the network.
     *
     * The network is captured once. A wireless terminal is a snapshot of "the system I could see when I
     * opened this": re-checking the range on every click would close the window under a player who took
     * one step, and the window closes on its own the moment they stop looking at it anyway.
     *
     * A session exists because this behavior does not. There is one [WirelessTerminalBehavior] per item
     * type, shared by everyone holding one, so nothing that belongs to a single open window can live on
     * it.
     */
    private inner class Session(private val player: Player, private val network: StorageNetwork) {

        private val content = TerminalContent { network }
        private val filter = TerminalContent.searchState()
        private val sortMode = TerminalContent.sortState()

        /**
         * Unpersisted, unlike the crafting terminal's: an item you carry has nowhere to keep nine stacks
         * between uses, so the grid is emptied back into the network when the window closes.
         */
        private val craftingInventory = VirtualInventory(null, CRAFTING_GRID_SIZE)

        private val grid = CraftingGrid(
            craftingInventory,
            { player.world },
            { network },
            { maxBulkCrafts },
            content::refresh
        )

        /**
         * Set while one of this session's windows is being replaced by another.
         *
         * Opening a window closes whatever the player had open, which fires that window's close handler —
         * and the close handler is what empties the crafting grid. Without this, stepping from the list to
         * the grid and back would put the parts away behind the player's back.
         */
        private var switching = false

        /**
         * Keeps the list current while the window is open.
         *
         * The block terminals get this from their own tick; an item has no tick, so without it the list a
         * wireless terminal shows is whatever the network held at the moment it was opened — and a pipe
         * emptying a drive bay behind the player's back would go unnoticed until they closed and reopened
         * it. Same interval as every other readout, and it stops with the session.
         */
        private var refreshTask: BukkitTask? = null

        //<editor-fold desc="screens", defaultstate="collapsed">

        fun openList() {
            val items = content.contentProvider(player, filter, sortMode, columns = 8, visibleSlots = 48)

            val gui = content.contentGui(
                items,
                "x x x x x x x x u",
                "x x x x x x x x s",
                "x x x x x x x x o",
                "x x x x x x x x f",
                "x x x x x x x x d",
                "x x x x x x x x m"
            ) {
                's' by searchButton(::openSearch)
                'o' by content.sortButton(sortMode)
                'f' by content.clearFilterButton(filter)
                'm' by modeButton(crafting = true)
            }

            show(GuiTextures.WIRELESS_TERMINAL, gui)
        }

        fun openCrafting() {
            val items = content.contentProvider(player, filter, sortMode, columns = 6, visibleSlots = 36)

            val gui = content.contentGui(
                items,
                "x x x x x x c c c",
                "x x x x x x c c c",
                "x x x x x x c c c",
                "x x x x x x u r d",
                "x x x x x x s o f",
                "x x x x x x m . ."
            ) {
                'c' by craftingInventory
                'r' by grid.resultItem()
                's' by searchButton(::openSearch)
                'o' by content.sortButton(sortMode)
                'f' by content.clearFilterButton(filter)
                'm' by modeButton(crafting = false)
            }

            show(GuiTextures.WIRELESS_CRAFTING, gui)
        }

        /**
         * The same anvil search the block terminals use, so looking something up feels the same wherever
         * you are standing.
         */
        private fun openSearch() {
            val items = content.contentProvider(player, filter, sortMode, columns = 8, visibleSlots = 32)

            val window = anvilWindow(player) {
                title by DefaultGuiTextures.SEARCH.component
                // 9x4, which InvUI requires of a split window's lower gui — see the storage terminal
                lowerGui by content.contentGui(
                    items,
                    "x x x x x x x x u",
                    "x x x x x x x x d",
                    "x x x x x x x x f",
                    "x x x x x x x x b"
                ) {
                    'f' by content.clearFilterButton(filter)
                    'b' by backButton(::openList)
                }
                text.subscribe(filter::set)

                // This window shows no grid, but it is still one of this session's screens, and closing
                // it ends the session — so it has to put the parts back for the same reason the others
                // do. Escaping out of the search used to take the whole grid with it.
                onClose {
                    if (!switching)
                        end()
                }
            }

            switching = true
            try {
                window.open()
            } finally {
                switching = false
            }
        }

        private fun show(texture: GuiTexture, gui: Gui) {
            val window = Window.builder()
                .setViewer(player)
                .setTitle(texture.getTitle(Component.translatable("menu.smartstorage.wireless.title")))
                .setUpperGui(gui)
                .addCloseHandler {
                    if (!switching)
                        end()
                }
                .build()

            content.refresh()

            switching = true
            try {
                window.open()
            } finally {
                switching = false
            }

            // after opening, so a window that fails to open leaves no task behind
            startRefreshing()
        }

        /**
         * Starts the poll that keeps the list current, once. Every screen of a session shares it, and
         * stepping between them must not restart it.
         */
        private fun startRefreshing() {
            if (refreshTask != null)
                return

            val interval = max(1, TERMINAL_REFRESH_TICKS).toLong()
            refreshTask = runTaskTimer(interval, interval) { content.refresh() }
        }

        /**
         * Closes the session down: no more window, so no more polling, and the crafting grid goes back
         * where it came from.
         */
        private fun end() {
            refreshTask?.cancel()
            refreshTask = null
            grid.returnContents(player)
        }

        //</editor-fold>

        private fun modeButton(crafting: Boolean): Item = item {
            itemProvider by ItemBuilder(if (crafting) Material.CRAFTING_TABLE else Material.CHEST).setName(
                Component.translatable(
                    if (crafting) "menu.smartstorage.wireless.to_crafting" else "menu.smartstorage.wireless.to_list",
                    NamedTextColor.GRAY
                ).withoutPreFormatting()
            )
            onClick {
                if (!clickType.isLeftClick)
                    return@onClick

                player.playClickSound()
                if (crafting) openCrafting() else openList()
            }
        }

    }

    /**
     * One slot, redrawn from the count on the item — so closing this window cannot lose an upgrade,
     * because there was never anything in it to lose.
     */
    private fun openUpgrades(player: Player, hand: EquipmentSlot) {
        // not a null check: an equipment slot hands back an empty stack rather than null, so asking
        // whether this is a terminal is both the question that matters and the only one with an answer
        val held = player.inventory.getItem(hand)
        if (of(held) == null)
            return

        val inventory = VirtualInventory(null, 1, arrayOfNulls(1), intArrayOf(maxUpgrades))

        val installed = upgrades(held)
        if (installed > 0)
            inventory.setItem(SELF, 0, UpgradeTypes.RANGE.item.createItemStack(installed))

        /**
         * The terminal this window is editing, as it was after the last write.
         *
         * The slot holds *real* upgrade items standing in for a number on an item, so the two have to be
         * kept in step or one of them is free. Re-reading the hand alone is not enough: a player who
         * slides the terminal aside and drops a second one into the same slot would have the withdrawal
         * decrement the newcomer — which had nothing to decrement — and walk away with items the first
         * terminal still counts as installed.
         *
         * Compared by similarity rather than by reference because Bukkit hands out a fresh mirror per
         * call. Two terminals only compare equal when their stored counts already agree, in which case
         * writing to either is the same write.
         */
        var expected = held.clone()

        inventory.addPreUpdateHandler { event ->
            if (event.updateReason == SELF)
                return@addPreUpdateHandler

            val newItem = event.newItem
            if (newItem != null && newItem.novaItem != UpgradeTypes.RANGE.item) {
                event.isCancelled = true
                return@addPreUpdateHandler
            }

            // an emptied slot reads as an empty stack, which is not a terminal, so one check covers both
            val terminal = player.inventory.getItem(hand)
            if (of(terminal) == null || !terminal.isSimilar(expected)) {
                event.isCancelled = true
                return@addPreUpdateHandler
            }

            setUpgrades(terminal, newItem?.amount ?: 0)
            player.inventory.setItem(hand, terminal)
            expected = terminal.clone()
        }

        Window.builder()
            .setViewer(player)
            .setTitle(GuiTextures.WIRELESS_UPGRADES.getTitle(Component.translatable("menu.smartstorage.wireless.upgrades")))
            .setUpperGui(
                Gui.builder()
                    .setStructure(
                        ". . . . . . . . .",
                        ". . . . u . . . .",
                        ". . . . . . . . ."
                    )
                    .addIngredient('u', inventory)
                    .build()
            )
            .build()
            .open()

        player.playClickSound()
    }

    private fun searchButton(open: () -> Unit): Item = item {
        itemProvider by ItemBuilder(Material.COMPASS).setName(
            Component.translatable("menu.smartstorage.terminal.search", NamedTextColor.GRAY).withoutPreFormatting()
        )
        onClick {
            if (clickType.isLeftClick) {
                player.playClickSound()
                open()
            }
        }
    }

    private fun backButton(back: () -> Unit): Item = item {
        itemProvider by ItemBuilder(Material.BARRIER).setName(
            Component.translatable("menu.smartstorage.terminal.back", NamedTextColor.GRAY).withoutPreFormatting()
        )
        onClick {
            if (clickType.isLeftClick) {
                player.playClickSound()
                back()
            }
        }
    }

    //</editor-fold>

    override fun modifyClientSideStack(player: Player?, server: ItemStack, client: ItemStack): ItemStack {
        val lore = client.lore() ?: mutableListOf()

        val pos = binding(server)
        lore += if (pos == null)
            Component.translatable("menu.smartstorage.wireless.unbound", NamedTextColor.RED).withoutPreFormatting()
        else
            Component.translatable(
                "menu.smartstorage.wireless.bound",
                NamedTextColor.GRAY,
                Component.text("${pos.x}, ${pos.y}, ${pos.z}", NamedTextColor.GREEN),
                Component.text(pos.world.name, NamedTextColor.GREEN)
            ).withoutPreFormatting()

        lore += Component.translatable(
            "menu.smartstorage.wireless.range",
            NamedTextColor.GRAY,
            Component.text(range(server).toInt(), NamedTextColor.GREEN),
            Component.text(upgrades(server), NamedTextColor.GREEN),
            Component.text(maxUpgrades, NamedTextColor.GREEN)
        ).withoutPreFormatting()

        lore += Component.translatable("menu.smartstorage.wireless.hint", NamedTextColor.DARK_GRAY)
            .withoutPreFormatting()

        client.lore(lore)
        return client
    }

    companion object : ItemBehaviorFactory<WirelessTerminalBehavior> {

        private const val BINDING_KEY = "wireless_binding"
        private const val UPGRADES_KEY = "wireless_upgrades"

        /**
         * Marks the writes this class makes to the upgrade slot, so seeding it from the item does not
         * come back round as a change to be written to the item.
         */
        private val SELF = object : UpdateReason {}

        override fun create(item: NovaItem) = WirelessTerminalBehavior(item)

        /**
         * The wireless terminal behavior of [itemStack], or null if it isn't one.
         */
        fun of(itemStack: ItemStack?): WirelessTerminalBehavior? =
            itemStack?.novaItem?.getBehaviorOrNull<WirelessTerminalBehavior>()

    }

}
