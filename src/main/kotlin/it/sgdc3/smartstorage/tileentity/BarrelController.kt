package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.TerminalContent
import it.sgdc3.smartstorage.registry.Blocks.BARREL_CONTROLLER
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.storage.SortMode
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
import xyz.xenondevs.invui.dsl.with
import xyz.xenondevs.invui.gui.Markers
import xyz.xenondevs.invui.inventory.event.ItemPreUpdateEvent
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.item.ItemProvider
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
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
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
 * How many slots the item network sees, so that it addresses the wall rather than the block it is
 * wired to.
 *
 * [EXPOSED_TIERS] per barrel rather than one, because a compacting barrel offers every rung of its
 * ladder — iron as blocks, as ingots, as nuggets — and each has to be a slot a pipe can name. Nova
 * reads the count once when a network is built.
 */
private val EXPOSED_SLOTS = MAX_BARRELS * EXPOSED_TIERS

/**
 * The drop-off slot in the controller's own menu. One, beside the readout, rather than filling the
 * sidebar's free corner: it empties into the wall on the next tick, so a second and a third would only
 * ever hold something for the fraction of a second between two shift-clicks.
 */
private const val DEPOSIT_SLOTS = 1

/**
 * Shared by every controller, for the same reason [StorageBarrel]'s is shared by every barrel.
 */
private val SHORTFALL = RateLimitedError()

/**
 * Every block that touches a cube: the six faces, the twelve edges and the eight corners.
 *
 * Built rather than written out because twenty-six triples typed by hand is twenty-six chances to leave
 * one out, and the one left out would be a barrel that silently stops being part of the wall.
 */
private val TOUCHING_OFFSETS: List<Triple<Int, Int, Int>> = buildList {
    for (dx in -1..1) for (dy in -1..1) for (dz in -1..1)
        if (dx != 0 || dy != 0 || dz != 0)
            add(Triple(dx, dy, dz))
}

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

    /**
     * Drop-off slots, pushed into the wall on the next tick.
     *
     * A real inventory rather than a clickable, and that is the whole point of it: shift-clicking out of
     * the player inventory is not a click on anything the menu drew, it is InvUI looking for somewhere in
     * the window to *put* a stack. With nothing but items in the gui there was nowhere, so the controller
     * was the one screen in the addon that could only be filled one cursor-load at a time — the terminals
     * and the barrel's own menu have had one of these all along.
     *
     * Whatever the wall will not take stays sitting in the slot rather than vanishing.
     */
    private val depositInventory = storedInventory("deposit", DEPOSIT_SLOTS)
        .apply { addPreUpdateHandler(::screenDeposit) }

    /**
     * Turns the drop-off slot away from anything the wall could not store anyway.
     *
     * Without this the slot takes the item and then cannot get rid of it: the drain finds no barrel
     * willing to hold it, so it sits there, and the next shift-click has nowhere to land. One stack of
     * something the wall does not want jams the whole slot.
     *
     * Refusing instead means the item never leaves the player's inventory, and a shift-click on it is
     * simply a click that does nothing — which is what makes it worth holding shift down a row of
     * mixed items. What the wall wants goes in; what it does not, stays put and gets skipped.
     *
     * This is a snapshot of a wall that is still moving, and deliberately so: an item refused now
     * because every barrel is full goes in a moment later once something drains one. Being briefly
     * wrong in the permissive direction costs a click; being wrong the other way would jam the slot,
     * which is the state this exists to prevent.
     */
    private fun screenDeposit(event: ItemPreUpdateEvent) {
        // the drain writes the leftover back through this same slot, and it must not be second-guessed:
        // by then the items are already in the wall, and refusing the write would duplicate them
        if (event.updateReason == SELF_UPDATE_REASON)
            return

        val incoming = event.newItem ?: return
        val type = ItemType.of(incoming) ?: return

        if (!accepts(type))
            event.isCancelled = true
    }

    private val entries: MutableProvider<List<Entry>> = mutableProvider(emptyList())

    override fun handleEnable() {
        super.handleEnable()
        rescan()
    }

    override suspend fun handleNetworkLoaded(state: NetworkState) = syncTouchingFaces(state)

    override suspend fun handleNetworkUpdate(state: NetworkState) = syncTouchingFaces(state)

    /**
     * The wall's mouth is as passive as the wall: it speaks to a pipe, not to whatever happens to be
     * beside it. See [closeTouchingItemFaces].
     */
    private suspend fun syncTouchingFaces(state: NetworkState) {
        // not extractOnlyFromBelow: a wall's mouth is meant to be served by one pipe on whichever side
        // is convenient, and sides that only accept would break that
        restrictItemFaces(state, pos, itemHolder, extractOnlyFromBelow = false)
        touching.refresh(state, pos)
    }

    override fun handleTick() {
        drainDeposit()

        // on a timer as well as on network updates, for the reason given in StorageBarrel.handleTick
        if (serverTick % max(1, RESCAN_TICKS) == 1)
            NetworkManager.queueWrite(pos.chunkPos, ::syncTouchingFaces)

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
     * Breadth-first through neighbouring barrels, claiming each one on the way.
     *
     * Neighbouring means all twenty-six blocks around one — faces, edges and corners — rather than the
     * six faces. A wall is something a player builds by eye, and by eye a barrel set kitty-corner to the
     * next is part of the same wall; requiring face contact made a diagonal step silently end it, which
     * is a rule nobody can see from the outside. It also lets a wall turn a corner or step up a level
     * without a filler barrel holding it together.
     *
     * The cost is four times the lookups per barrel, which is affordable because that is all they are:
     * a map lookup and a chunk check, bounded by `max_barrels` and repeated every `rescan_ticks`.
     *
     * A barrel belongs to exactly one controller — see [StorageBarrel.claim] — so a second controller
     * built onto the same wall reaches nothing rather than presenting the same storage twice. Its menu
     * says so, which is the only honest way for it to fail. Diagonals widen what "the same wall" means:
     * two runs of barrels passing corner to corner are now one, and whichever controller scans first
     * owns them both.
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

            for ((dx, dy, dz) in TOUCHING_OFFSETS) {
                if (found.size >= limit)
                    break

                val next = BlockPos(pos.world, current.x + dx, current.y + dy, current.z + dz)
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

    /**
     * Whether any barrel on the wall would take [type] right now.
     *
     * Each barrel answers for itself — see [StorageBarrel.accepts], which is the same rule its insert
     * applies. The wall does not restate it, because a second copy would drift from the first and the
     * drift would show up as a drop-off slot that swallows something no barrel then wants.
     */
    fun accepts(type: ItemType): Boolean = barrels.any { it.accepts(type) }

    fun countOf(type: ItemType): Long {
        var total = 0L
        for (barrel in barrels) total += barrel.countOf(type)
        return total
    }

    /**
     * One line per barrel, at its densest rung — see
     * [BarrelBacking.collectInto][StorageConnector] for why never more than one.
     */
    fun collectInto(index: MutableMap<ItemType, Long>) {
        for (barrel in barrels) {
            val (type, held) = barrel.offers().firstOrNull() ?: continue
            index.merge(type, held) { a, b -> a + b }
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

    /**
     * Undoes an [insert] across the wall, in the units it was made in — see [StorageBarrel.retract].
     */
    fun retract(type: ItemType, amount: Long) {
        var left = amount
        for (barrel in barrels) {
            if (left <= 0L) break
            left -= barrel.retract(type, left)
        }
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

    /**
     * Pushes whatever is sitting in the drop-off slots into the wall.
     *
     * Modelled on [AbstractTerminal]'s, including the rollback: [insert] has already committed the items
     * to the barrels by the time the slot is written, and the slot can refuse the write, so taking back
     * exactly what went in is the only way to stop them existing in the wall and in the slot at once.
     *
     * Note that [insert] answers with what it *stored*, the opposite of what a storage network's returns.
     */
    private fun drainDeposit() {
        // most ticks there is nothing here, and this runs per controller per tick — getUnsafeItem
        // because the guard only needs to look at the slots, not own a copy of them
        if ((0..<depositInventory.size).none { depositInventory.getUnsafeItem(it)?.isEmpty == false })
            return

        var moved = false
        for (slot in 0..<depositInventory.size) {
            val stack = depositInventory.getItem(slot) ?: continue
            val type = ItemType.of(stack) ?: continue

            val stored = insert(type, stack.amount.toLong()).toInt()
            if (stored <= 0)
                continue

            val left = stack.amount - stored
            val updated = if (left <= 0) null else stack.clone().apply { amount = left }
            // retract, not extract: a compacting barrel was handed ingots and is holding blocks, so
            // asking it for the ingots back gets nothing and the items exist twice
            if (depositInventory.setItem(SELF_UPDATE_REASON, slot, updated)) moved = true else retract(type, stored.toLong())
        }

        if (moved)
            refreshEntries()
    }

    private fun refreshEntries() {
        // What the wall *holds*, not which block holds it: one line per item, summed across every
        // barrel. Two barrels of cobblestone are one line, because "there is cobblestone here" is the
        // question the list answers, and which barrel it sits in is the wall's business.
        //
        // Empty barrels contribute nothing at all. An empty barrel is a place to put something rather
        // than something the wall holds, and the drop-off slot is how you fill one.
        val totals = LinkedHashMap<ItemType, Long>()
        for (barrel in barrels) {
            // A compacting barrel counts at every rung of its ladder, so the wall can be asked for
            // ingots as readily as for blocks. Those lines are **the same iron at three densities**,
            // not three stocks — which is exactly why they are summed *per item* and never across.
            //
            // The same list the network is offered, so the menu cannot show a line a pipe cannot ask for.
            for ((tier, held) in barrel.offers())
                totals.merge(tier, held, Long::plus)
        }

        val next = totals.map { (type, amount) -> Entry(type, amount) }

        // the provider invalidates on version rather than on value, so setting an equal list would
        // still rebuild every row for a wall nobody has touched
        if (next != entries.get())
            entries.set(next)
    }

    /**
     * One row of the controller's menu: what a barrel held the last time the wall was scanned.
     */
    /**
     * One line of the wall's contents: an item, and how much of it the whole wall has.
     *
     * Carries no barrel, and that is the point — the list is of what is stored, not of what is storing
     * it, so a click acts on the wall and lets it decide which barrel answers.
     */
    private data class Entry(val type: ItemType, val amount: Long)

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

        // not usedCount: that counts whole stored items, and a compacting barrel worn down to a part of
        // a block still holds something a pipe can be given — see StorageBarrel.hasContents
        override fun isEmpty(): Boolean = barrels.none(StorageBarrel::hasContents)

        /**
         * The wall as slots: one per barrel, and one per *rung* for the barrels that compact.
         *
         * A compacting barrel appears three times over — as blocks, as ingots, as nuggets — so a pipe
         * can ask it for whichever it wants. **Those slots are one stock seen three ways.** Nothing may
         * add them up, and nothing does: [canTake] and [take] both read the barrel live rather than the
         * snapshot, so the moment blocks leave through one slot the other two answer smaller. The
         * snapshot only ever records which barrel and which rung a slot number meant.
         */
        override fun copyContents(destination: Array<ItemStack>) {
            val slots = arrayOfNulls<Pair<StorageBarrel, ItemType>>(EXPOSED_SLOTS)
            var at = 0

            for (barrel in this@BarrelController.barrels) {
                if (at >= EXPOSED_SLOTS)
                    break

                // The barrel's own answer, capped at its own budget: the wall must address it exactly as
                // a pipe pressed straight against it would, and a second copy of "what can be taken out
                // of here" would drift from the first without anybody noticing.
                for ((type, held) in barrel.offers()) {
                    if (at >= EXPOSED_SLOTS)
                        break

                    slots[at] = barrel to type
                    destination[at] = type.createStack(min(held, type.maxStackSize.toLong()).toInt())
                    at++
                }
            }

            while (at < EXPOSED_SLOTS) {
                destination[at] = ItemStack.empty()
                at++
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
        private val sortMode = TerminalContent.sortState()

        /** 7 columns x 4 rows in the controller itself, 8 x 3 in the search window's lower gui. */
        private val rows = rowsProvider(columns = 7, visibleSlots = 28)
        private val searchRows = rowsProvider(columns = 8, visibleSlots = 24)

        /**
         * The list, padded out to whole rows with slots that take what you drop on them.
         *
         * The padding is why an item can go into *any* cell of the grid rather than only onto a line
         * that already holds one: a wall with three things in it still shows a full screen, and every
         * empty square of it is somewhere to put a fourth. Without it, filling a barrel with something
         * new meant finding the drop-off slot in the corner.
         */
        private fun rowsProvider(columns: Int, visibleSlots: Int): Provider<List<Item>> =
            combinedProvider(entries, filter, sortMode) { list, text, mode ->
                // Rendering an item's name and flattening it to plain text is the expensive part, so it
                // is only done when there is something to match against — a wall nobody is searching
                // costs nothing more than it did before.
                val matching = if (text.isBlank()) list else list.filter { entry ->
                    ItemUtils.getName(entry.type.stack).toPlainText(player).contains(text, ignoreCase = true)
                }

                val items = sorted(matching, mode).mapTo(ArrayList<Item>(matching.size)) { rowItem(it) }

                // fill up to a whole number of rows, and never leave the first screen half-dead
                val lines = (items.size + columns - 1) / columns
                repeat(max(visibleSlots, lines * columns) - items.size) { items += depositTargetItem() }

                items
            }

        /**
         * An empty square that swallows whatever is dropped on it into the wall. The gui texture draws
         * the slot underneath, so there is nothing to render.
         */
        private fun depositTargetItem(): Item = item {
            itemProvider by ItemProvider.EMPTY
            onClick {
                if (!player.itemOnCursor.isEmpty)
                    depositCursor(player, all = !clickType.isRightClick)
            }
        }

        /**
         * The wall's contents in the order the player asked for.
         *
         * Sorting by name renders one name per line, which the search deliberately avoids doing unless
         * it has to. It is affordable here for the same reason the scan is: a wall is at most
         * `max_barrels` lines, not a whole network's worth of item types.
         */
        private fun sorted(entries: List<Entry>, mode: SortMode): List<Entry> = when (mode) {
            SortMode.AMOUNT -> entries.sortedByDescending { it.amount }
            SortMode.NAME -> entries.sortedBy { ItemUtils.getName(it.type.stack).toPlainText(player) }
        }

        override val gui = listGui(
            rows,
            "x x x x x x x s u",
            "x x x x x x x f d",
            "x x x x x x x o .",
            "x x x x x x x p i"
        ) {
            's' by searchButton()
            'f' by clearFilterButton()
            'o' by sortButton()
            'i' by statusItem
            // only on this gui: the search window's lower half is all list and has nowhere to put it
            'p' by depositInventory.with(TerminalContent.depositBackground())
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
            items: Provider<List<Item>>,
            vararg structure: String,
            extraIngredients: IngredientsDsl.() -> Unit = {}
        ) = scrollItemsGui(*structure) {
            // see TerminalContent.contentGui: the orientation names what a line is, not the scroll
            // direction, and VERTICAL would scroll this sideways under an up and a down arrow
            'x' by Markers.CONTENT_LIST_SLOT_HORIZONTAL
            'u' by scrollUpItem(line)
            'd' by scrollDownItem(line, maxLine)
            extraIngredients()
            content by items
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

        /**
         * The same button the terminals have, written out here rather than borrowed: theirs is an
         * instance method of [TerminalContent], which exists to index a storage network, and a barrel
         * wall is not on one. Six lines is cheaper than that dependency.
         */
        private fun sortButton(): Item = item {
            itemProvider by sortMode.map { mode ->
                ItemBuilder(Material.HOPPER).setName(
                    Component.translatable(mode.localizationKey, NamedTextColor.GRAY).withoutPreFormatting()
                )
            }
            onClick {
                if (clickType.isLeftClick) {
                    sortMode.set(sortMode.get().next())
                    player.playClickSound()
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
                // Four rows and three rows of results, which is not a contradiction: InvUI requires a
                // split window's lower gui to be exactly 9x4 because it *is* the player inventory —
                // three rows and the hotbar — while Nova's search background only frames the three.
                // The hotbar row is drawn detached below the panel, so anything listed there sits
                // outside it. It is left empty, and carries the one button that has to be reachable.
                lowerGui by listGui(
                    searchRows,
                    "x x x x x x x x u",
                    "x x x x x x x x d",
                    "x x x x x x x x f",
                    ". . . . . . . . b"
                ) {
                    'f' by clearFilterButton()
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
                    depositCursor(player, all = !clickType.isRightClick)
                    return@onClick
                }

                val stackSize = entry.type.maxStackSize
                when {
                    clickType.isShiftClick -> takeToInventory(player, entry.type, stackSize)
                    clickType == ClickType.LEFT -> takeToCursor(player, entry.type, stackSize)
                    clickType == ClickType.RIGHT -> takeToCursor(player, entry.type, max(1, stackSize / 2))
                    else -> return@onClick
                }
            }
        }

        private fun rowIcon(entry: Entry): ItemBuilder =
            ItemBuilder(entry.type.createStack(max(1, min(entry.amount, entry.type.maxStackSize.toLong()).toInt())))
                .setLore(
                    listOf(
                        Component.translatable(
                            "menu.smartstorage.terminal.stored",
                            NamedTextColor.GRAY,
                            Component.text(entry.amount, NamedTextColor.GREEN)
                        ).withoutPreFormatting(),
                        Component.translatable("menu.smartstorage.barrel.hint", NamedTextColor.DARK_GRAY)
                            .withoutPreFormatting()
                    )
                )

        private fun depositCursor(player: Player, all: Boolean) {
            val cursor = player.itemOnCursor
            val candidate = ItemType.of(cursor) ?: return

            val offered = if (all) cursor.amount else 1
            // the wall, not one barrel: it routes to whichever holds this already, and only then to an
            // empty one — see BarrelController.insert
            val stored = insert(candidate, offered.toLong()).toInt()
            if (stored <= 0)
                return

            val left = cursor.amount - stored
            player.setItemOnCursor(if (left <= 0) null else candidate.createStack(left))
            refreshEntries()
        }

        private fun takeToCursor(player: Player, type: ItemType, count: Int) {
            if (!player.itemOnCursor.isEmpty)
                return

            val taken = extract(type, count.toLong()).toInt()
            if (taken <= 0)
                return

            player.setItemOnCursor(type.createStack(taken))
            refreshEntries()
        }

        private fun takeToInventory(player: Player, type: ItemType, count: Int) {
            val taken = extract(type, count.toLong()).toInt()
            if (taken <= 0)
                return

            val leftover = player.inventory.addItemCorrectly(type.createStack(taken))
            if (leftover > 0) {
                // back into the wall, and onto the floor for whatever it will no longer take
                val rejected = leftover - insert(type, leftover.toLong()).toInt()
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
