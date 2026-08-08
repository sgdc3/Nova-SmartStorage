package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.registry.Blocks.BARREL_CONTROLLER
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.util.RateLimitedError
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.provider.MutableProvider
import xyz.xenondevs.commons.provider.Provider
import xyz.xenondevs.commons.provider.combinedProvider
import xyz.xenondevs.commons.provider.mutableProvider
import xyz.xenondevs.invui.dsl.IngredientsDsl
import xyz.xenondevs.invui.dsl.anvilWindow
import xyz.xenondevs.invui.dsl.item
import xyz.xenondevs.invui.dsl.scrollItemsGui
import xyz.xenondevs.invui.gui.Markers
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.ui.menu.item.scrollDownItem
import xyz.xenondevs.nova.ui.menu.item.scrollUpItem
import xyz.xenondevs.nova.ui.overlay.guitexture.DefaultGuiTextures
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.addItemCorrectly
import xyz.xenondevs.nova.util.component.adventure.toPlainText
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.item.ItemUtils
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.format.WorldDataManager
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

private val MAX_BARRELS by BARREL_CONTROLLER.config.entry<Int>("max_barrels")
private val RESCAN_TICKS by BARREL_CONTROLLER.config.entry<Int>("rescan_ticks")

/**
 * One barrel per exposed slot, so the item network sees the wall rather than the block it is wired to.
 */
private val EXPOSED_SLOTS = MAX_BARRELS

/**
 * Shared by every controller, for the same reason [StorageBarrel]'s is shared by every barrel.
 */
private val SHORTFALL = RateLimitedError()

/**
 * Speaks for every [StorageBarrel] it can reach, so that one pipe — or one [StorageConnector] — serves
 * a whole wall of them.
 *
 * Reach means *touching*: the controller looks at its six neighbours, then at theirs, and so on through
 * barrels only, up to `max_barrels`. Nothing has to be wired and nothing has to be configured; barrels
 * stacked against each other are one block of storage, which is the arrangement players build anyway.
 *
 * Like a barrel it is an ordinary item network end point and deliberately not a device on this addon's
 * own storage network — see [StorageBarrel] for why. Putting a storage connector against the controller
 * is what brings the whole wall into a virtual network, in one block instead of one per barrel.
 *
 * The scan is repeated rather than driven by events because there is nothing to hang an event on: a
 * barrel placed at the far end of a wall is not a change to anything the controller can observe. It is
 * a handful of map lookups per barrel and touches no block state, which is what makes repeating it
 * affordable.
 */
class BarrelController(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(pos, state, data) {

    /**
     * The barrels this controller currently reaches, in the order the scan found them — which is
     * breadth first from the controller, so the nearest barrels keep the lowest slots and a pipe's view
     * of the wall stays stable as it grows.
     *
     * Published as a whole so the network threads always read one coherent list.
     */
    @Volatile
    var barrels: List<StorageBarrel> = emptyList()
        private set

    private val networkedInventory = ControllerInventory()
    private val itemHolder = storedItemHolder(networkedInventory to NetworkConnectionType.BUFFER)

    /**
     * What this controller is touching, so that it can decline all of it. A controller is the wall's
     * mouth, and the wall is passive: it speaks to a pipe, not to whatever happens to be beside it.
     * See [TouchingInventories].
     */
    private val touching = TouchingInventories()

    private val entries: MutableProvider<List<Entry>> = mutableProvider(emptyList())

    override fun handleEnable() {
        super.handleEnable()
        rescan()
    }

    override suspend fun handleNetworkLoaded(state: NetworkState) = touching.refresh(state, pos)

    override suspend fun handleNetworkUpdate(state: NetworkState) = touching.refresh(state, pos)

    override fun handleTick() {
        // No controller powers this one — it is not on the storage network at all — so its screen goes
        // dark when it reaches no barrels, which is the same statement about itself that every other
        // device's lights make: "I am doing something".
        setPowered(barrels.isNotEmpty())

        // the scan is cheap but not free, and nothing it looks at changes within a tick
        if (serverTick % max(1, RESCAN_TICKS) != 0)
            return

        rescan()
    }

    override fun handleDisable() {
        releaseAll()
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        releaseAll()
        super.handleBreak(ctx)
    }

    private fun releaseAll() {
        barrels.forEach { it.release(this) }
        barrels = emptyList()
    }

    /**
     * Whether this controller currently speaks for [barrel]. Used by the barrel to decide whether an
     * older claim on it is still worth anything.
     */
    fun covers(barrel: StorageBarrel): Boolean = barrel in barrels

    private fun rescan() {
        val previous = barrels
        val found = scan()
        barrels = found

        // a wall that shrank leaves barrels claimed by a controller that no longer reaches them, and
        // nothing else would ever let go of them
        if (previous.isNotEmpty()) {
            val kept = found.toHashSet()
            for (barrel in previous)
                if (barrel !in kept) barrel.release(this)
        }

        // rebuilding the rows means walking every barrel; nothing reads them until someone is looking
        if (menuContainer.getMenus<TileEntityMenu>().any()) {
            refreshEntries()
            menuContainer.forEachMenu(BarrelControllerMenu::update)
        }
    }

    /**
     * Breadth-first through touching barrels, claiming each one on the way.
     *
     * A barrel belongs to exactly one controller — see [StorageBarrel.claim] — so a second controller
     * built onto the same wall reaches nothing rather than presenting the same storage twice. Its menu
     * says so, which is the only honest way for it to fail.
     *
     * The chunk check comes first for the same reason it does in [StorageConnector]: asking Bukkit
     * whether a chunk is loaded neither loads it nor allocates, while reading anything out of it would
     * load it — and a controller at a chunk border has neighbours in the next one.
     */
    private fun scan(): List<StorageBarrel> {
        val limit = max(0, MAX_BARRELS)
        if (limit == 0)
            return emptyList()

        val found = ArrayList<StorageBarrel>(min(limit, 16))
        val visited = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()

        visited += pos
        queue += pos

        while (queue.isNotEmpty() && found.size < limit) {
            val current = queue.removeFirst()

            for (face in CUBE_FACES) {
                if (found.size >= limit)
                    break

                val next = current.advance(face)
                if (!visited.add(next))
                    continue
                if (!pos.world.isChunkLoaded(next.x shr 4, next.z shr 4))
                    continue

                val barrel = WorldDataManager.getTileEntity(next) as? StorageBarrel ?: continue
                if (!barrel.isEnabled)
                    continue

                // another controller owns this one, and walking past it would let this controller
                // reach round the back of a wall it does not own
                if (!barrel.claim(this))
                    continue

                found += barrel
                queue += next
            }
        }

        return found
    }

    //<editor-fold desc="the wall as one piece of storage", defaultstate="collapsed">

    val hasRoom: Boolean
        get() = barrels.any(StorageBarrel::hasRoom)

    val usedTypes: Int
        get() = barrels.count { it.storedType != null }

    val usedCount: Long
        get() {
            var total = 0L
            for (barrel in barrels) total += barrel.storedAmount
            return total
        }

    val totalCount: Long
        get() {
            var total = 0L
            for (barrel in barrels) total += barrel.capacity
            return total
        }

    fun holds(type: ItemType): Boolean = barrels.any { it.holds(type) }

    fun countOf(type: ItemType): Long {
        var total = 0L
        for (barrel in barrels) total += barrel.countOf(type)
        return total
    }

    fun collectInto(index: MutableMap<ItemType, Long>) {
        for (barrel in barrels) {
            val type = barrel.storedType ?: continue
            val amount = barrel.storedAmount
            if (amount > 0L)
                index.merge(type, amount) { a, b -> a + b }
        }
    }

    /**
     * Stores up to [amount] items of [type] across the wall and returns how many went in.
     *
     * Barrels already dedicated to the item come first — including one that was locked onto it and then
     * emptied, which is precisely the barrel the player meant it to go into — so pushing cobblestone at
     * a sorted wall does not scatter it into whichever barrel happened to be free.
     */
    fun insert(type: ItemType, amount: Long): Long {
        val barrels = this.barrels
        var left = amount

        for (barrel in barrels) {
            if (left <= 0L) break
            if (barrel.storedType == type)
                left -= barrel.insert(type, left)
        }
        for (barrel in barrels) {
            if (left <= 0L) break
            left -= barrel.insert(type, left)
        }

        return amount - left
    }

    fun extract(type: ItemType, amount: Long): Long {
        var extracted = 0L

        for (barrel in barrels) {
            if (extracted >= amount) break
            extracted += barrel.extract(type, amount - extracted)
        }

        return extracted
    }

    //</editor-fold>

    private fun refreshEntries() {
        val next = barrels.map { Entry(it, it.storedType, it.storedAmount, it.capacity) }

        // the provider invalidates on version rather than on value, so setting an equal list would
        // still rebuild every row for a wall nobody has touched
        if (next != entries.get())
            entries.set(next)
    }

    /**
     * One row of the controller's menu: what a barrel held the last time the wall was scanned.
     */
    private data class Entry(
        val barrel: StorageBarrel,
        val type: ItemType?,
        val amount: Long,
        val capacity: Long
    )

    /**
     * The whole wall as Nova's item network sees it: one slot per barrel.
     *
     * The mapping from slot to barrel is recorded by [copyContents], which the distributor calls once
     * per network tick before it addresses any slot — so a slot always resolves back to the barrel and
     * the item type it stood for when the snapshot was taken, even if the wall changed in between.
     */
    internal inner class ControllerInventory : NetworkedInventory {

        /**
         * What each snapshot slot referred to, written by [copyContents].
         */
        @Volatile
        private var slots: Array<Pair<StorageBarrel, ItemType>?> = arrayOfNulls(EXPOSED_SLOTS)

        override val uuid: UUID
            get() = this@BarrelController.uuid

        override val size: Int
            get() = EXPOSED_SLOTS

        fun covers(barrel: StorageBarrel): Boolean = barrel in barrels

        override fun add(itemStack: ItemStack, amount: Int): Int {
            val candidate = ItemType.of(itemStack) ?: return amount
            // both this and the storage connector's view of the wall go through the same routing
            return amount - insert(candidate, amount.toLong()).toInt()
        }

        /**
         * On the same terms [StorageBarrel.extract] answers on — `isEnabled` included, which counting
         * alone does not check. See [StorageBarrel.BarrelInventory.canTake].
         */
        override fun canTake(slot: Int, amount: Int): Boolean {
            val (barrel, type) = slots.getOrNull(slot) ?: return false
            return barrel.isEnabled && barrel.countOf(type) >= amount
        }

        /**
         * A short take here is items created out of nothing — see
         * [NetworkView.take][it.sgdc3.smartstorage.network.NetworkView.take].
         */
        override fun take(slot: Int, amount: Int) {
            val (barrel, type) = slots.getOrNull(slot) ?: return
            val taken = barrel.extract(type, amount.toLong())

            if (taken < amount) {
                SHORTFALL.log {
                    "Barrel wall at $pos handed out $amount× $type but only had $taken: " +
                        "${amount - taken} item(s) were created."
                }
            }
        }

        override fun isFull(): Boolean = !hasRoom

        override fun isEmpty(): Boolean = usedCount <= 0L

        override fun copyContents(destination: Array<ItemStack>) {
            val barrels = this@BarrelController.barrels
            val slots = arrayOfNulls<Pair<StorageBarrel, ItemType>>(EXPOSED_SLOTS)

            for (i in 0..<EXPOSED_SLOTS) {
                val barrel = barrels.getOrNull(i)
                val type = barrel?.storedType
                val amount = barrel?.storedAmount ?: 0L

                if (type == null || amount <= 0L) {
                    destination[i] = ItemStack.empty()
                    continue
                }

                slots[i] = barrel to type
                destination[i] = type.createStack(min(amount, type.maxStackSize.toLong()).toInt())
            }

            this.slots = slots
        }

        /**
         * A barrel this controller speaks for is not a separate piece of storage, and neither is a
         * second controller that reaches the same wall. Left to itself the distributor would happily
         * move a barrel's contents into itself, forever.
         */
        override fun canExchangeItemsWith(other: NetworkedInventory): Boolean {
            if (other === this)
                return false

            if (other in touching)
                return false

            return when (other) {
                is StorageBarrel.BarrelInventory -> !covers(other.barrel)
                is ControllerInventory -> other.barrels().none(::covers)
                else -> true
            }
        }

        private fun barrels(): List<StorageBarrel> = this@BarrelController.barrels

    }

    /**
     * Per player rather than shared, because the search text is: two people looking at the same wall
     * must not be typing into each other's list.
     */
    @TileEntityMenuClass
    inner class BarrelControllerMenu(player: Player) : IndividualTileEntityMenu(player, GuiTextures.BARREL_CONTROLLER) {

        private val statusItem = ClickableItem({ statusIcon() })
        private val filter: MutableProvider<String> = mutableProvider("")

        private val rows: Provider<List<Item>> = combinedProvider(entries, filter) { list, text ->
            // Rendering an item's name and flattening it to plain text is the expensive part, so it is
            // only done when there is something to match against — a wall nobody is searching costs
            // nothing more than it did before.
            val matching = if (text.isBlank()) list else list.filter { entry ->
                val type = entry.type ?: return@filter false
                ItemUtils.getName(type.stack).toPlainText(player).contains(text, ignoreCase = true)
            }

            matching.mapTo(ArrayList(matching.size)) { rowItem(it) }
        }

        override val gui = listGui(
            "x x x x x x x s u",
            "x x x x x x x f d",
            "x x x x x x x . .",
            "x x x x x x x . i"
        ) {
            's' by searchButton()
            'i' by statusItem
        }

        init {
            // the tile entity only rebuilds the rows while somebody is looking, so this is what makes
            // the first look current; the gui is bound to the provider rather than to a snapshot of it
            refreshEntries()
        }

        fun update() = statusItem.notifyWindows()

        /**
         * The scrolling list of barrels, shared by the menu and the search window: the same rows, the
         * same clicks, a different frame around them.
         */
        private fun listGui(
            vararg structure: String,
            extraIngredients: IngredientsDsl.() -> Unit = {}
        ) = scrollItemsGui(*structure) {
            // see TerminalContent.contentGui: the orientation names what a line is, not the scroll
            // direction, and VERTICAL would scroll this sideways under an up and a down arrow
            'x' by Markers.CONTENT_LIST_SLOT_HORIZONTAL
            'u' by scrollUpItem(line)
            'd' by scrollDownItem(line, maxLine)
            'f' by clearFilterButton()
            extraIngredients()
            content by rows
        }

        private fun searchButton(): Item = item {
            itemProvider by ItemBuilder(Material.COMPASS).setName(
                Component.translatable("menu.smartstorage.terminal.search", NamedTextColor.GRAY).withoutPreFormatting()
            )
            onClick {
                if (clickType.isLeftClick) {
                    player.playClickSound()
                    openSearch()
                }
            }
        }

        private fun clearFilterButton(): Item = item {
            itemProvider by filter.map { text ->
                ItemBuilder(Material.NAME_TAG).setName(
                    Component.translatable(
                        "menu.smartstorage.terminal.filter",
                        NamedTextColor.GRAY,
                        Component.text(text.ifBlank { "-" }, NamedTextColor.GREEN)
                    ).withoutPreFormatting()
                )
            }
            onClick {
                if (clickType.isLeftClick && filter.get().isNotBlank()) {
                    filter.set("")
                    player.playClickSound()
                }
            }
        }

        /**
         * An anvil window whose text field feeds the filter, with the barrels taking the place of the
         * player inventory — the same shape the terminals use, so searching feels the same everywhere.
         */
        private fun openSearch() {
            val window = anvilWindow(player) {
                // Nova's own search background, so the anvil stops looking like an anvil
                title by DefaultGuiTextures.SEARCH.component
                lowerGui by listGui(
                    "x x x x x x x x u",
                    "x x x x x x x x d",
                    "x x x x x x x x f",
                    "x x x x x x x x b"
                ) {
                    'b' by backButton()
                }
                text.subscribe(filter::set)
            }

            menuContainer.registerWindow(window)
            window.open()
        }

        private fun backButton(): Item = item {
            itemProvider by ItemBuilder(Material.BARRIER).setName(
                Component.translatable("menu.smartstorage.terminal.back", NamedTextColor.GRAY).withoutPreFormatting()
            )
            onClick {
                if (clickType.isLeftClick) {
                    player.playClickSound()
                    openWindow()
                }
            }
        }

        private fun rowItem(entry: Entry) = item {
            itemProvider by rowIcon(entry)
            onClick {
                if (!player.itemOnCursor.isEmpty) {
                    depositCursor(player, entry.barrel, all = !clickType.isRightClick)
                    return@onClick
                }

                val type = entry.barrel.storedType ?: return@onClick
                val stackSize = type.maxStackSize
                when {
                    clickType.isShiftClick -> takeToInventory(player, entry.barrel, type, stackSize)
                    clickType == ClickType.LEFT -> takeToCursor(player, entry.barrel, type, stackSize)
                    clickType == ClickType.RIGHT -> takeToCursor(player, entry.barrel, type, max(1, stackSize / 2))
                    else -> return@onClick
                }
            }
        }

        private fun rowIcon(entry: Entry): ItemBuilder {
            val type = entry.type

            val builder = if (type == null) {
                ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).setName(
                    Component.translatable("menu.smartstorage.barrel.empty", NamedTextColor.GRAY)
                        .withoutPreFormatting()
                )
            } else {
                ItemBuilder(type.createStack(max(1, min(entry.amount, type.maxStackSize.toLong()).toInt())))
            }

            return builder.setLore(
                listOf(
                    Component.translatable(
                        "menu.smartstorage.barrel.stored",
                        NamedTextColor.GRAY,
                        Component.text(entry.amount, NamedTextColor.GREEN),
                        Component.text(entry.capacity, NamedTextColor.GREEN)
                    ).withoutPreFormatting(),
                    Component.translatable("menu.smartstorage.barrel.hint", NamedTextColor.DARK_GRAY)
                        .withoutPreFormatting()
                )
            )
        }

        private fun depositCursor(player: Player, barrel: StorageBarrel, all: Boolean) {
            val cursor = player.itemOnCursor
            val candidate = ItemType.of(cursor) ?: return

            val offered = if (all) cursor.amount else 1
            val stored = barrel.insert(candidate, offered.toLong()).toInt()
            if (stored <= 0)
                return

            val left = cursor.amount - stored
            player.setItemOnCursor(if (left <= 0) null else candidate.createStack(left))
            refreshEntries()
        }

        private fun takeToCursor(player: Player, barrel: StorageBarrel, type: ItemType, count: Int) {
            if (!player.itemOnCursor.isEmpty)
                return

            val taken = barrel.extract(type, count.toLong()).toInt()
            if (taken <= 0)
                return

            player.setItemOnCursor(type.createStack(taken))
            refreshEntries()
        }

        private fun takeToInventory(player: Player, barrel: StorageBarrel, type: ItemType, count: Int) {
            val taken = barrel.extract(type, count.toLong()).toInt()
            if (taken <= 0)
                return

            val leftover = player.inventory.addItemCorrectly(type.createStack(taken))
            if (leftover > 0) {
                // back into the barrel, and onto the floor for whatever it will no longer take
                val rejected = leftover - barrel.insert(type, leftover.toLong()).toInt()
                if (rejected > 0)
                    player.world.dropItemNaturally(player.location, type.createStack(rejected))
            }

            refreshEntries()
        }

        private fun statusIcon(): ItemBuilder {
            val barrels = this@BarrelController.barrels

            return ItemBuilder(Material.CHEST)
                .setName(
                    Component.translatable("menu.smartstorage.barrel_controller.title").withoutPreFormatting()
                )
                .setLore(
                    listOf(
                        Component.translatable(
                            "menu.smartstorage.barrel_controller.barrels",
                            if (barrels.isEmpty()) NamedTextColor.RED else NamedTextColor.GRAY,
                            Component.text(barrels.size, NamedTextColor.GREEN),
                            Component.text(MAX_BARRELS, NamedTextColor.GREEN)
                        ).withoutPreFormatting(),
                        Component.translatable(
                            "menu.smartstorage.barrel_controller.types",
                            NamedTextColor.GRAY,
                            Component.text(usedTypes, NamedTextColor.GREEN)
                        ).withoutPreFormatting(),
                        Component.translatable(
                            "menu.smartstorage.barrel_controller.items",
                            NamedTextColor.GRAY,
                            Component.text(usedCount, NamedTextColor.GREEN),
                            Component.text(totalCount, NamedTextColor.GREEN)
                        ).withoutPreFormatting()
                    )
                )
        }

    }

}
