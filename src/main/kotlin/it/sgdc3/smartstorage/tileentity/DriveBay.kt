package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.priorityIcon
import it.sgdc3.smartstorage.item.FluidCellBehavior
import it.sgdc3.smartstorage.item.StorageCellBehavior
import it.sgdc3.smartstorage.network.DEFAULT_PRIORITY
import it.sgdc3.smartstorage.network.FluidProvider
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.network.PRIORITY_RANGE
import it.sgdc3.smartstorage.network.StorageEndPoint
import it.sgdc3.smartstorage.network.StorageProvider
import it.sgdc3.smartstorage.registry.Blocks.DRIVE_BAY
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.UpgradeTypes
import it.sgdc3.smartstorage.storage.CellData
import it.sgdc3.smartstorage.storage.FluidCellData
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.storage.StorageLock
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.inventory.VirtualInventory
import xyz.xenondevs.invui.inventory.event.ItemPostUpdateEvent
import xyz.xenondevs.invui.inventory.event.ItemPreUpdateEvent
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.addon.simpleupgrades.gui.OpenUpgradesItem
import xyz.xenondevs.nova.addon.simpleupgrades.storedUpgradeHolder
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.util.NumberFormatUtils
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.ui.menu.item.AddNumberItem
import xyz.xenondevs.nova.ui.menu.item.RemoveNumberItem
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import java.util.UUID
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * The hard ceiling on disk slots. The inventory is always this big; slots past the current limit are
 * refused, because a [xyz.xenondevs.invui.inventory.VirtualInventory] cannot be resized after creation.
 */
private const val MAX_CELL_SLOTS = 12

private val BASE_SLOTS by DRIVE_BAY.config.entry<Int>("base_slots")

/**
 * Holds the disks that give a network its capacity — storage cells for items, fluid cells for water and
 * lava, in the same slots and in any mixture.
 *
 * One rack rather than two because that is what a player is building: a wall of disks, not a wall of
 * item disks beside a wall of fluid disks. Every slot therefore carries at most one of the two kinds,
 * and the arrays below are parallel because a slot's contents are whichever of them is not null.
 *
 * Cell contents live on the cell items, but decoding and re-encoding them costs one NBT round trip per
 * stored type, which is far too much to pay per inserted item. So the bay keeps the decoded [CellData]
 * in memory and writes it back once per *operation* rather than once per item — see [flushCell] for why
 * it cannot be once per tick, which is what it used to be.
 */
class DriveBay(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(pos, state, data), StorageEndPoint, StorageProvider, FluidProvider {

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    private val upgradeHolder = storedUpgradeHolder(UpgradeTypes.STORAGE)

    /**
     * Cells that no longer fit after the inventory shrank, handed back to the world on enable.
     */
    private val orphanedCells = ArrayList<ItemStack>()

    private val cellInventory = resolveCellInventory()

    private val priorityValue = storedValue("priority") { DEFAULT_PRIORITY }
    override var priority: Int by priorityValue

    private val cells = arrayOfNulls<CellData>(MAX_CELL_SLOTS)
    private val behaviors = arrayOfNulls<StorageCellBehavior>(MAX_CELL_SLOTS)

    private val fluidCells = arrayOfNulls<FluidCellData>(MAX_CELL_SLOTS)
    private val fluidBehaviors = arrayOfNulls<FluidCellBehavior>(MAX_CELL_SLOTS)

    /**
     * One flag per slot, whichever kind of disk is in it: a slot holds one or the other, never both.
     */
    private val dirty = BooleanArray(MAX_CELL_SLOTS)

    /**
     * How many disk slots are currently usable: the base amount plus whatever the storage upgrades add.
     */
    val cellSlots: Int
        get() = min(MAX_CELL_SLOTS, BASE_SLOTS + upgradeHolder.getValue(UpgradeTypes.STORAGE))

    init {
        holders += storageHolder

        // priorities used to run negative; the menus now show them with Nova's numbered GUI item, which
        // has no model below zero. The same migration a storage connector's ports do.
        if (priority !in PRIORITY_RANGE)
            priority = priority.coerceIn(PRIORITY_RANGE)

        for (slot in 0..<MAX_CELL_SLOTS)
            loadCell(slot)

        // taking an upgrade back out has to hand the now unreachable cells back to the world,
        // otherwise their contents would be stranded in a slot nothing can address
        upgradeHolder.getValueProvider(UpgradeTypes.STORAGE).subscribe { ejectLockedCells() }
    }

    //<editor-fold desc="cell bookkeeping", defaultstate="collapsed">

    /**
     * Returns the cell inventory, resizing it to [MAX_CELL_SLOTS] if an older version of this addon
     * saved a smaller one.
     *
     * `storedInventory` only honours the requested size when it *creates* the inventory — an existing
     * tile entity keeps whatever was persisted, and a [VirtualInventory] cannot be resized in place. So
     * a stored inventory of the wrong size has to be rebuilt and swapped in, or every slot index past
     * the old size throws.
     */
    private fun resolveCellInventory(): VirtualInventory {
        val stored = storedValue("cells") { newCellInventory(arrayOfNulls(MAX_CELL_SLOTS)) }
        val existing = stored.get()

        val inventory = if (existing.size == MAX_CELL_SLOTS) {
            existing
        } else {
            val items = arrayOfNulls<ItemStack>(MAX_CELL_SLOTS)
            for (slot in 0..<existing.size) {
                val stack = existing.getItem(slot) ?: continue
                if (slot < MAX_CELL_SLOTS) items[slot] = stack else orphanedCells += stack
            }

            newCellInventory(items).also(stored::set)
        }

        inventory.addPreUpdateHandler(::handleCellPreUpdate)
        inventory.addPostUpdateHandler(::handleCellPostUpdate)
        dropProvider { inventory.items.filterNotNull() }
        return inventory
    }

    private fun newCellInventory(items: Array<ItemStack?>) = VirtualInventory(
        UUID.nameUUIDFromBytes("cells".toByteArray()),
        MAX_CELL_SLOTS,
        items,
        IntArray(MAX_CELL_SLOTS) { 1 }
    )

    private fun loadCell(slot: Int) {
        val stack = cellInventory.getItem(slot)

        val behavior = StorageCellBehavior.of(stack)
        behaviors[slot] = behavior
        cells[slot] = if (behavior != null && stack != null) behavior.read(stack) else null

        val fluidBehavior = FluidCellBehavior.of(stack)
        fluidBehaviors[slot] = fluidBehavior
        fluidCells[slot] = if (fluidBehavior != null && stack != null) fluidBehavior.read(stack) else null

        dirty[slot] = false
    }

    /**
     * Writes a cell's live contents back onto its item stack.
     *
     * Uses [getUnsafeItem][xyz.xenondevs.invui.inventory.Inventory.getUnsafeItem] to reach the stack the
     * inventory actually holds and mutates it in place, so this can be called from inside an inventory
     * event without triggering another one.
     *
     * **This has to have happened before anyone can click the slot, which is why every change flushes
     * immediately rather than waiting for the tick.** Flushing from the pre-update handler is not enough
     * and never was: InvUI clones the clicked stack *before* it fires that event
     * (`AbstractGui.handleInvLeftClick` takes `inventory.getItem(slot)` first, then hands that same clone
     * to the cursor), so an in-place mutation made during the event lands on the stack left behind, not
     * on the one the player walks away with. A cell that lost items earlier in the same tick would be
     * handed over still listing them — with the items themselves already somewhere else.
     *
     * The batching that used to defer this to [handleTick] therefore bought less than it looked like:
     * one encode per network operation rather than per tick, which under load is the same thing, since
     * a transfer usually touches one cell once.
     */
    private fun flushCell(slot: Int) {
        if (!dirty[slot])
            return

        dirty[slot] = false

        val stack = cellInventory.getUnsafeItem(slot) ?: return

        val data = cells[slot]
        val behavior = behaviors[slot]
        if (data != null && behavior != null) {
            behavior.write(stack, data)
            return
        }

        val fluidData = fluidCells[slot]
        val fluidBehavior = fluidBehaviors[slot]
        if (fluidData != null && fluidBehavior != null)
            fluidBehavior.write(stack, fluidData)
    }

    private fun flushCells() {
        for (slot in 0..<MAX_CELL_SLOTS)
            flushCell(slot)
    }

    private fun ejectLockedCells() {
        if (!isEnabled)
            return

        for (slot in cellSlots..<MAX_CELL_SLOTS) {
            flushCell(slot)

            val stack = cellInventory.getItem(slot) ?: continue
            cellInventory.setItem(SELF_UPDATE_REASON, slot, null)
            cells[slot] = null
            behaviors[slot] = null
            fluidCells[slot] = null
            fluidBehaviors[slot] = null

            pos.world.dropItemNaturally(pos.location.add(0.5, 1.0, 0.5), stack)
        }

        notifyMenus()
    }

    private fun handleCellPreUpdate(event: ItemPreUpdateEvent) {
        if (event.updateReason == SELF_UPDATE_REASON)
            return

        val newItem = event.newItem
        if (newItem != null) {
            val isCell = StorageCellBehavior.of(newItem) != null || FluidCellBehavior.of(newItem) != null
            if (!isCell || event.slot >= cellSlots) {
                event.isCancelled = true
                return
            }
        }

        // A cell must never leave the bay carrying stale contents. Every slot is flushed, not just this
        // one, because the post-update handler re-reads a slot from its item: any cell still holding
        // un-written items at that point would silently lose them.
        //
        // Under the lock, because this is the one path into the cells that does *not* come from the
        // network. A transfer landing between this flush and the reload below would write into a
        // CellData whose item has already been handed to the player — items into an object nothing will
        // read again.
        StorageLock.withLock { flushCells() }
    }

    private fun handleCellPostUpdate(event: ItemPostUpdateEvent) {
        if (event.updateReason == SELF_UPDATE_REASON)
            return

        StorageLock.withLock { loadCell(event.slot) }
        notifyMenus()
    }

    private fun notifyMenus() {
        menuContainer.forEachMenu(DriveBayMenu::update)
    }

    //</editor-fold>

    //<editor-fold desc="StorageProvider", defaultstate="collapsed">

    private inline fun forEachCell(action: (Int, CellData) -> Unit) {
        val limit = cellSlots
        for (i in 0..<limit) {
            val cell = cells[i] ?: continue
            action(i, cell)
        }
    }

    private inline fun forEachFluidCell(action: (Int, FluidCellData) -> Unit) {
        val limit = cellSlots
        for (i in 0..<limit) {
            val cell = fluidCells[i] ?: continue
            action(i, cell)
        }
    }

    /**
     * Every installed disk, of either kind — this is what the network's energy draw is priced on, and a
     * fluid cell is no cheaper to keep spinning than a storage cell.
     */
    override val cellCount: Int
        get() {
            var count = 0
            forEachCell { _, _ -> count++ }
            forEachFluidCell { _, _ -> count++ }
            return count
        }

    override val usedTypes: Int
        get() {
            var total = 0
            forEachCell { _, cell -> total += cell.usedTypes }
            return total
        }

    override val totalTypes: Int
        get() {
            var total = 0
            forEachCell { _, cell -> total += cell.maxTypes }
            return total
        }

    override val usedCount: Long
        get() {
            var total = 0L
            forEachCell { _, cell -> total += cell.total }
            return total
        }

    override val totalCount: Long
        get() {
            var total = 0L
            forEachCell { _, cell -> total += cell.maxItems }
            return total
        }

    override fun collectInto(index: MutableMap<ItemType, Long>) =
        forEachCell { _, cell -> cell.collectInto(index) }

    override fun countOf(type: ItemType): Long {
        var total = 0L
        forEachCell { _, cell -> total += cell.countOf(type) }
        return total
    }

    override fun holds(type: ItemType): Boolean {
        for (slot in 0..<cellSlots) {
            val cell = cells[slot] ?: continue
            if (cell.countOf(type) > 0L)
                return true
        }
        return false
    }

    override fun insert(type: ItemType, amount: Long): Long {
        var left = amount

        // fill cells that already hold this type first, so a stack doesn't get scattered needlessly
        forEachCell { slot, cell ->
            if (left > 0L && cell.countOf(type) > 0L)
                left -= insertInto(slot, cell, type, left)
        }
        forEachCell { slot, cell ->
            if (left > 0L)
                left -= insertInto(slot, cell, type, left)
        }

        val inserted = amount - left
        if (inserted > 0L) {
            flushCells()
            notifyMenus()
        }
        return inserted
    }

    private fun insertInto(slot: Int, cell: CellData, type: ItemType, amount: Long): Long {
        val inserted = cell.insert(type, amount)
        if (inserted > 0L)
            dirty[slot] = true
        return inserted
    }

    override fun extract(type: ItemType, amount: Long): Long {
        var extracted = 0L

        forEachCell { slot, cell ->
            if (extracted < amount) {
                val taken = cell.extract(type, amount - extracted)
                if (taken > 0L) {
                    dirty[slot] = true
                    extracted += taken
                }
            }
        }

        if (extracted > 0L) {
            flushCells()
            notifyMenus()
        }
        return extracted
    }

    //</editor-fold>

    //<editor-fold desc="FluidProvider", defaultstate="collapsed">

    override val usedAmount: Long
        get() {
            var total = 0L
            forEachFluidCell { _, cell -> total += cell.total }
            return total
        }

    override val totalAmount: Long
        get() {
            var total = 0L
            forEachFluidCell { _, cell -> total += cell.maxAmount }
            return total
        }

    override fun collectFluidsInto(index: MutableMap<FluidType, Long>) =
        forEachFluidCell { _, cell -> cell.collectInto(index) }

    override fun amountOf(type: FluidType): Long {
        var total = 0L
        forEachFluidCell { _, cell -> total += cell.amountOf(type) }
        return total
    }

    override fun holdsFluid(type: FluidType): Boolean {
        for (slot in 0..<cellSlots) {
            val cell = fluidCells[slot] ?: continue
            if (cell.amountOf(type) > 0L)
                return true
        }
        return false
    }

    override fun insertFluid(type: FluidType, amount: Long): Long {
        var left = amount

        // fill cells that already hold this fluid first, so a bucket does not get split needlessly
        forEachFluidCell { slot, cell ->
            if (left > 0L && cell.amountOf(type) > 0L)
                left -= insertFluidInto(slot, cell, type, left)
        }
        forEachFluidCell { slot, cell ->
            if (left > 0L)
                left -= insertFluidInto(slot, cell, type, left)
        }

        val inserted = amount - left
        if (inserted > 0L) {
            flushCells()
            notifyMenus()
        }
        return inserted
    }

    private fun insertFluidInto(slot: Int, cell: FluidCellData, type: FluidType, amount: Long): Long {
        val inserted = cell.insert(type, amount)
        if (inserted > 0L)
            dirty[slot] = true
        return inserted
    }

    override fun extractFluid(type: FluidType, amount: Long): Long {
        var extracted = 0L

        forEachFluidCell { slot, cell ->
            if (extracted < amount) {
                val taken = cell.extract(type, amount - extracted)
                if (taken > 0L) {
                    dirty[slot] = true
                    extracted += taken
                }
            }
        }

        if (extracted > 0L) {
            flushCells()
            notifyMenus()
        }
        return extracted
    }

    //</editor-fold>

    override fun handleEnable() {
        super.handleEnable()

        if (orphanedCells.isNotEmpty()) {
            orphanedCells.forEach { pos.world.dropItemNaturally(pos.location.add(0.5, 1.0, 0.5), it) }
            orphanedCells.clear()
        }
    }

    override fun handleTick() {
        flushCells()
        setPowered(storageNetwork?.isOnline == true)
    }

    override fun handleDisable() {
        flushCells()
        storageNetwork = null
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        flushCells()
        storageNetwork = null
        super.handleBreak(ctx)
    }

    override fun saveData() {
        flushCells()
        super.saveData()
    }

    override fun getDrops(includeSelf: Boolean): List<ItemStack> {
        // drops are collected from the cell inventory, so the contents must be on the items by now
        flushCells()
        return super.getDrops(includeSelf)
    }

    @TileEntityMenuClass
    inner class DriveBayMenu : GlobalTileEntityMenu(GuiTextures.DRIVE_BAY) {

        private val statusItem = ClickableItem({ statusIcon() })

        private val priorityItem = ClickableItem({ priorityIcon(priority) })

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . u",
                ". c c c c c c . p",
                ". c c c c c c . v",
                ". i . . . . . . m"
            )
            .addIngredient('c', cellInventory)
            .addIngredient('u', OpenUpgradesItem(upgradeHolder))
            .addIngredient('i', statusItem)
            .addIngredient('v', priorityItem)
            .addIngredient('p', AddNumberItem({ PRIORITY_RANGE }, { priority }, ::setPriority, "menu.smartstorage.priority_up"))
            .addIngredient('m', RemoveNumberItem({ PRIORITY_RANGE }, { priority }, ::setPriority, "menu.smartstorage.priority_down"))
            .build()

        fun update() {
            statusItem.notifyWindows()
            priorityItem.notifyWindows()
        }

        private fun setPriority(value: Int) {
            priority = value
            update()
        }

        private fun statusIcon(): ItemBuilder {
            val builder = ItemBuilder(Material.PAPER)
            builder.setName(Component.translatable("menu.smartstorage.drive_bay.title").withoutPreFormatting())

            val lore = ArrayList<Component>()
            lore += Component.translatable(
                "menu.smartstorage.drive_bay.slots",
                NamedTextColor.GRAY,
                Component.text(cellSlots, NamedTextColor.GREEN),
                Component.text(MAX_CELL_SLOTS, NamedTextColor.GREEN)
            ).withoutPreFormatting()
            lore += Component.translatable(
                "menu.smartstorage.priority",
                NamedTextColor.GRAY,
                Component.text(priority, NamedTextColor.GREEN)
            ).withoutPreFormatting()
            lore += Component.translatable(
                "menu.smartstorage.drive_bay.items",
                NamedTextColor.GRAY,
                Component.text(usedCount, NamedTextColor.GREEN),
                Component.text(totalCount, NamedTextColor.GREEN)
            ).withoutPreFormatting()
            lore += Component.translatable(
                "menu.smartstorage.drive_bay.types",
                NamedTextColor.GRAY,
                Component.text(usedTypes, NamedTextColor.GREEN),
                Component.text(totalTypes, NamedTextColor.GREEN)
            ).withoutPreFormatting()

            // only when there is a fluid cell in the rack: a bay of storage cells being told it holds
            // 0 of 0 buckets is noise about a thing that is not there
            val fluidCapacity = totalAmount
            if (fluidCapacity > 0L) {
                lore += Component.translatable(
                    "menu.smartstorage.drive_bay.fluid",
                    NamedTextColor.GRAY,
                    Component.text(NumberFormatUtils.getFluidString(usedAmount, fluidCapacity), NamedTextColor.GREEN)
                ).withoutPreFormatting()
            }

            builder.setLore(lore)
            return builder
        }

    }

}
