package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.registry.BlockStateProperties
import it.sgdc3.smartstorage.registry.Blocks.STORAGE_BARREL
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.UpgradeTypes
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.storage.StorageLock
import it.sgdc3.smartstorage.util.RateLimitedError
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.provider.MutableProvider
import xyz.xenondevs.invui.Click
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.addon.simpleupgrades.gui.OpenUpgradesItem
import xyz.xenondevs.nova.addon.simpleupgrades.storedUpgradeHolder
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.context.intention.BlockInteract
import xyz.xenondevs.nova.util.addItemCorrectly
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.InteractionResult
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.world.item.DefaultGuiItems
import java.util.UUID
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

private val BASE_STACKS by STORAGE_BARREL.config.entry<Int>("base_stacks")

/**
 * The stack size a barrel is measured against while it holds nothing. Only ever used to print a
 * capacity for an empty barrel, since what it can really take is not known until something is in it.
 */
private const val NOMINAL_STACK_SIZE = 64

/**
 * Shared by every barrel, so a wall of them cannot multiply one bug into a flood of identical lines.
 */
private val SHORTFALL = RateLimitedError()

/**
 * A barrel that holds one kind of item, a great many of them, and says on its front what and how many.
 *
 * The capacity is counted in *stacks*, so a barrel takes as many shulker boxes as it does cobblestone
 * — which is the rule that keeps it fair — and each Storage Upgrade doubles it. That is the whole of
 * the model: one type, one number, one multiplier.
 *
 * ## What it is on which network
 *
 * To Nova this is an ordinary item network end point: it registers an [ItemHolder][xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder]
 * with a single `BUFFER` container, so item cables, hoppers and machines reach it with no integration
 * on either side.
 *
 * To *this* addon's storage network it is deliberately nothing at all. It carries no
 * [StorageHolder][it.sgdc3.smartstorage.network.StorageHolder], so a storage cable will not bridge to
 * it and a controller will never count it as a device. A barrel joins a virtual network the same way a
 * chest does: by having a [StorageConnector] placed against it, which finds it exactly as it finds any
 * other Nova end point with a buffer.
 *
 * That is a design decision rather than an omission. A wall of barrels is meant to be *storage a
 * player can see*, and letting it wire straight into a controller would make it a second, parallel
 * kind of drive with none of a cell's limits. Going through a connector — or a [BarrelController], for
 * a whole wall at once — keeps one rule: what the virtual network holds is whatever its connectors can
 * reach.
 *
 * ## Threading
 *
 * The contents are read and written from Nova's item network ticker as well as from the server thread,
 * so every one of them happens under [StorageLock]. The front display and the menus are packets and
 * are therefore left to [handleTick], which only has to look at one volatile flag to find out whether
 * there is anything to redraw.
 */
class StorageBarrel(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(pos, state, data) {

    private val upgradeHolder = storedUpgradeHolder(UpgradeTypes.STORAGE, UpgradeTypes.VOID)

    /**
     * Whether this barrel burns what it has no room for.
     *
     * Read on every insert, so it is cached rather than walked: `getValue` goes through a map and a
     * config list, and the network asks a full wall of barrels this question several times a second.
     * The provider below keeps it current.
     */
    @Volatile
    private var voids: Boolean = upgradeHolder.getValue(UpgradeTypes.VOID) > 0

    private val typeValue: MutableProvider<ItemStack?> = storedValue("type")
    private val amountValue = storedValue("amount") { 0L }
    private val lockedValue = storedValue("locked") { false }

    /**
     * The authoritative contents. The providers above are the persistence mirror, written inside the
     * same critical section, so there is only ever one place that decides what the barrel holds.
     */
    private var type: ItemType? = ItemType.of(typeValue.get())
    private var amount: Long = max(0L, amountValue.get())

    /**
     * Cached because capacity is derived from it on every insert, and reading it off the stack is a
     * data component lookup rather than a field.
     */
    private var typeStackSize: Int = type?.maxStackSize ?: NOMINAL_STACK_SIZE

    private var locked: Boolean by lockedValue

    /**
     * Drop-off slot, drained into the barrel on the next tick. Anything the barrel will not take stays
     * sitting here instead of disappearing.
     */
    private val depositInventory = storedInventory("deposit", 1)

    private val networkedInventory = BarrelInventory()
    private val itemHolder = storedItemHolder(networkedInventory to NetworkConnectionType.BUFFER)

    private var face: BarrelFace? = null

    /**
     * Set whenever the contents change, cleared by [handleTick] once the front and the menus have
     * caught up. Volatile because it is written from the network thread and read from the server one.
     */
    @Volatile
    private var dirty = true

    init {
        // taking an upgrade back out can leave the barrel over its new capacity, and items that no
        // longer fit have to go somewhere the player can pick them up
        upgradeHolder.getValueProvider(UpgradeTypes.STORAGE).subscribe { ejectOverflow() }

        upgradeHolder.getValueProvider(UpgradeTypes.VOID).subscribe { level ->
            voids = level > 0
            dirty = true
        }
    }

    //<editor-fold desc="contents", defaultstate="collapsed">

    /**
     * How many stacks fit, before the stack size of whatever is inside is taken into account.
     */
    val stacks: Int
        get() = max(1, BASE_STACKS * upgradeHolder.getValue(UpgradeTypes.STORAGE))

    /**
     * How many individual items fit. For an empty barrel this is a nominal figure: what actually fits
     * is not known until something is in it.
     */
    val capacity: Long
        get() = StorageLock.withLock { stacks.toLong() * (type?.let { typeStackSize } ?: NOMINAL_STACK_SIZE) }

    /**
     * The controller that speaks for this barrel, if one has claimed it.
     *
     * A barrel belongs to at most one controller, and that is the invariant everything else rests on:
     * a [StorageConnector] resolves a claimed barrel to its controller rather than to the barrel, so
     * touching a wall and touching the block that speaks for it are recognised as the same storage. Two
     * providers over one barrel would not merely double a readout — see
     * [StorageProvider.storageIdentity][it.sgdc3.smartstorage.network.StorageProvider.storageIdentity]
     * for how that turns into items being created.
     */
    @Volatile
    var controller: BarrelController? = null
        private set

    /**
     * Claims this barrel for [candidate], unless a controller that is still alive and still reaching
     * this far already has it. Answers whether the claim holds.
     *
     * First come, first served, and it heals itself: a controller that is broken, unloaded or no longer
     * within reach loses the barrel to the next one that asks. Main thread only — every caller is a
     * controller's tick.
     */
    fun claim(candidate: BarrelController): Boolean {
        val current = controller
        if (current != null && current !== candidate && current.isEnabled && current.covers(this))
            return false

        controller = candidate
        return true
    }

    fun release(candidate: BarrelController) {
        if (controller === candidate)
            controller = null
    }

    val storedType: ItemType?
        get() = StorageLock.withLock { type }

    val storedAmount: Long
        get() = StorageLock.withLock { amount }

    /**
     * A voiding barrel is never full: that is the whole of the feature, since nothing will offer items
     * to a provider that says it has no room.
     */
    val hasRoom: Boolean
        get() = voids || StorageLock.withLock { amount < capacity }

    fun holds(candidate: ItemType): Boolean =
        StorageLock.withLock { type == candidate && amount > 0L }

    fun countOf(candidate: ItemType): Long =
        StorageLock.withLock { if (type == candidate) amount else 0L }

    /**
     * Stores up to [count] items of [candidate] and returns how many were actually stored.
     *
     * An empty barrel takes on the first type offered to it; one that already has a type — because it
     * still holds items, or because it was locked onto one — takes nothing else.
     *
     * **With a Void Upgrade in it, a full barrel still reports everything as stored.** Those items are
     * gone: that is what the upgrade is for, and it is the only place in this addon where a returned
     * figure means "dealt with" rather than "kept". It still refuses another type, so a barrel voiding
     * cobblestone cannot swallow somebody's diamonds by being pointed at.
     */
    fun insert(candidate: ItemType, count: Long): Long = insertCounted(candidate, count).handled

    /**
     * The outcome of an insertion, told apart because with a Void Upgrade the two figures differ.
     *
     * [handled] is what the caller may stop worrying about; [kept] is what is really in the barrel
     * because of it. Anything that has to *undo* an insertion has to undo [kept] — undoing [handled]
     * would pull items out of a full voiding barrel that were never put into it.
     */
    private class Inserted(val handled: Long, val kept: Long) {

        companion object {

            val NONE = Inserted(0L, 0L)

        }

    }

    /**
     * [insert], with both figures, computed in one critical section so they cannot disagree.
     */
    private fun insertCounted(candidate: ItemType, count: Long): Inserted {
        // a barrel whose chunk has gone has already written its contents out; anything stored after
        // that is stored into an object nothing will ever read again
        if (count <= 0L || !isEnabled)
            return Inserted.NONE

        return StorageLock.withLock {
            val current = type
            if (current != null && current != candidate)
                return@withLock Inserted.NONE

            // computed before adopting, so a barrel never ends up displaying a type it stored nothing of
            val stackSize = if (current != null) typeStackSize else candidate.maxStackSize
            val stored = min(count, stacks.toLong() * stackSize - amount)

            if (stored <= 0L)
                return@withLock if (voids && current != null) Inserted(count, 0L) else Inserted.NONE

            if (current == null)
                setType(candidate, stackSize)
            setAmount(amount + stored)

            Inserted(if (voids) count else stored, stored)
        }
    }

    /**
     * Removes up to [count] items of [candidate] and returns how many were actually removed.
     */
    fun extract(candidate: ItemType, count: Long): Long {
        // see insert: taking from a barrel that has already been saved hands out items the world will
        // still have when the chunk comes back
        if (count <= 0L || !isEnabled)
            return 0L

        return StorageLock.withLock {
            if (type != candidate)
                return@withLock 0L

            val taken = min(count, amount)
            if (taken <= 0L)
                return@withLock 0L

            setAmount(amount - taken)
            // an unlocked barrel forgets what it held once it is empty, so it can be reused without
            // being emptied by hand; locking is how a player says "this one is for iron, always"
            if (amount == 0L && !locked)
                setType(null, NOMINAL_STACK_SIZE)

            taken
        }
    }

    /**
     * Both writes go through here so that the persisted mirror can never be forgotten. Callers hold
     * [StorageLock].
     */
    private fun setType(next: ItemType?, stackSize: Int) {
        type = next
        typeStackSize = stackSize
        typeValue.set(next?.stack)
        dirty = true
    }

    private fun setAmount(next: Long) {
        amount = next
        amountValue.set(next)
        dirty = true
    }

    /**
     * Hands back whatever no longer fits after the capacity shrank. Main thread only — it spawns items.
     */
    private fun ejectOverflow() {
        if (!isEnabled)
            return

        val overflow = StorageLock.withLock {
            val current = type ?: return@withLock null
            val excess = amount - capacity
            if (excess <= 0L)
                return@withLock null

            setAmount(amount - excess)
            current to excess
        } ?: return

        val (current, excess) = overflow
        for (stack in split(current, excess))
            pos.world.dropItemNaturally(pos.location.add(0.5, 1.0, 0.5), stack)
    }

    /**
     * [count] items of [candidate] as whole stacks.
     */
    private fun split(candidate: ItemType, count: Long): List<ItemStack> {
        val stackSize = candidate.maxStackSize
        val stacks = ArrayList<ItemStack>(((count + stackSize - 1) / stackSize).toInt())

        var left = count
        while (left > 0L) {
            val take = min(left, stackSize.toLong()).toInt()
            stacks += candidate.createStack(take)
            left -= take
        }

        return stacks
    }

    //</editor-fold>

    //<editor-fold desc="lifecycle", defaultstate="collapsed">

    override fun handleEnable() {
        super.handleEnable()

        val facing = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH
        face = BarrelFace(pos, facing)
        dirty = true
    }

    override fun handleTick() {
        drainDeposit()

        if (!dirty)
            return
        dirty = false

        applyLockedState()
        refreshFace()
        menuContainer.forEachMenu(StorageBarrelMenu::update)
    }

    /**
     * Puts the padlock on the front, or takes it off.
     *
     * The switch lives in the tile entity, but a texture can only follow block state, so the two have to
     * be kept in step. Done from the tick rather than from the click so that a barrel whose data and
     * block state ever drift apart — placed from an item, restored from an older save — corrects itself
     * without anyone having to touch it.
     */
    private fun applyLockedState() {
        if (blockState[BlockStateProperties.LOCKED] == locked)
            return

        updateBlockState(blockState.with(BlockStateProperties.LOCKED, locked))
    }

    override fun handleDisable() {
        face?.clear()
        face = null
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        face?.clear()
        face = null
        // Before super, which is what tears the tile entity down: the drops were already collected by
        // the time anything here runs — Nova asks a block what it drops and only then breaks it — so
        // this is the first moment the count is safe to clear and the last one it can still be written.
        clearContents()
        super.handleBreak(ctx)
    }

    /**
     * Adds the contents to whatever the block itself drops.
     *
     * Deliberately does *not* empty the barrel, which is what [handleBreak] is for. Nova calls this from
     * `BlockUtils.getDrops` as well as from a real break, and that is a plain question — `BlockManager`
     * exposes it to any plugin wanting to know what a block would drop. A barrel that emptied itself
     * whenever it was asked would lose everything to something merely looking at it.
     */
    override fun getDrops(includeSelf: Boolean): List<ItemStack> {
        val drops = ArrayList(super.getDrops(includeSelf))

        StorageLock.withLock {
            val current = type
            if (current != null && amount > 0L)
                drops += split(current, amount)
        }

        return drops
    }

    /**
     * Empties the barrel once it is genuinely being taken out of the world.
     *
     * It has to happen, and it has to happen here: Nova collects the drops before the break and nothing
     * afterwards would clear the count, so a second reader — the item that gets placed back down, most
     * of all — would find the contents still listed and hand the same items out again.
     */
    private fun clearContents() {
        StorageLock.withLock {
            if (amount > 0L || type != null) {
                setAmount(0L)
                setType(null, NOMINAL_STACK_SIZE)
            }
        }
    }

    private fun refreshFace() {
        val face = this.face ?: return
        val (current, count) = StorageLock.withLock { type to amount }

        if (current == null) {
            face.update(null, null)
            return
        }

        face.update(
            current.createStack(1),
            Component.text(count, if (count > 0L) NamedTextColor.WHITE else NamedTextColor.GRAY)
        )
    }

    private fun drainDeposit() {
        // the common case by far, and cheaper than anything that follows: getUnsafeItem only has to
        // look at the slot, not copy it
        val stack = depositInventory.getUnsafeItem(0)?.takeUnless(ItemStack::isEmpty) ?: return

        val candidate = ItemType.of(stack) ?: return

        val inserted = insertCounted(candidate, stack.amount.toLong())
        if (inserted.handled <= 0L)
            return

        val left = stack.amount - inserted.handled.toInt()
        val updated = if (left <= 0) null else candidate.createStack(left)
        // The slot can refuse the write, and items that are in the barrel and in the slot at once have
        // been created out of nothing. What comes back out is what actually went in — see [Inserted] for
        // why that is not the same number when the barrel voids.
        if (!depositInventory.setItem(SELF_UPDATE_REASON, 0, updated))
            extract(candidate, inserted.kept)
    }

    //</editor-fold>

    //<editor-fold desc="interaction", defaultstate="collapsed">

    /**
     * Right-clicking with an item puts it in, the way every barrel mod this is modelled on works —
     * shift to empty the whole inventory of that type into it. An empty hand opens the menu instead,
     * which is what [use] does by default.
     *
     * Falls through to [use] when nothing could be stored, so a right-click that does not fit still
     * opens the barrel rather than doing nothing at all.
     */
    override fun useItemOn(ctx: Context<BlockInteract>): InteractionResult {
        val player = ctx[BlockInteract.SOURCE_PLAYER] ?: return InteractionResult.Pass
        val hand = ctx[BlockInteract.HELD_HAND] ?: return InteractionResult.Pass
        val held = player.inventory.getItem(hand)
        val candidate = ItemType.of(held) ?: return InteractionResult.Pass

        val stored = if (player.isSneaking) {
            depositAll(player, candidate)
        } else {
            val taken = insert(candidate, held.amount.toLong()).toInt()
            if (taken > 0) {
                val left = held.amount - taken
                player.inventory.setItem(hand, if (left <= 0) null else candidate.createStack(left))
            }
            taken
        }

        if (stored <= 0)
            return InteractionResult.Pass

        // the stacks were taken out of the inventory outside of any inventory event, so the client is
        // still drawing them until it is told otherwise
        player.updateInventory()
        player.playClickSound()
        // the held item is not "used" in Minecraft's sense — nothing is consumed, worn down or put on
        // cooldown — so no ItemAction is attached; the stacks were moved by hand above
        return InteractionResult.Success(swing = true)
    }

    /**
     * Empties every stack of [candidate] the player is carrying into the barrel, and returns how many
     * items went in.
     */
    private fun depositAll(player: Player, candidate: ItemType): Int {
        val contents = player.inventory.storageContents
        var stored = 0

        for (slot in contents.indices) {
            val stack = contents[slot] ?: continue
            if (!candidate.matches(stack))
                continue

            val taken = insert(candidate, stack.amount.toLong()).toInt()
            if (taken <= 0)
                break

            stored += taken
            val left = stack.amount - taken
            player.inventory.setItem(slot, if (left <= 0) null else candidate.createStack(left))
        }

        return stored
    }

    //</editor-fold>

    /**
     * The barrel as Nova's item network sees it: one slot, holding one stack's worth of whatever is
     * inside, however many more of them there really are.
     *
     * One slot rather than an honest number of them because that is what the barrel *is* — the
     * distributor addresses slots, and there is only one thing here to address. The count it reads is
     * capped at a stack, which costs nothing: the distributor moves at most a stack per transfer
     * anyway, and it takes a fresh snapshot every tick.
     */
    internal inner class BarrelInventory : NetworkedInventory {

        val barrel: StorageBarrel
            get() = this@StorageBarrel

        override val uuid: UUID
            get() = this@StorageBarrel.uuid

        override val size: Int
            get() = 1

        override fun add(itemStack: ItemStack, amount: Int): Int {
            val candidate = ItemType.of(itemStack) ?: return amount
            return amount - insert(candidate, amount.toLong()).toInt()
        }

        /**
         * A promise, so it is asked on the same terms [extract] answers on — `isEnabled` included. A
         * barrel whose chunk went in between would otherwise say yes and then give nothing, and the
         * distributor has already handed the items over by the time it finds out.
         */
        override fun canTake(slot: Int, amount: Int): Boolean =
            slot == 0 && isEnabled && storedAmount >= amount

        /**
         * A short take here is items created out of nothing, and there is no way to say so — see
         * [NetworkView.take][it.sgdc3.smartstorage.network.NetworkView.take] for why that is structurally
         * impossible today and why it is checked anyway.
         */
        override fun take(slot: Int, amount: Int) {
            if (slot != 0)
                return

            val current = storedType ?: return
            val taken = extract(current, amount.toLong())

            if (taken < amount) {
                SHORTFALL.log {
                    "Barrel at $pos handed out $amount× $current but only had $taken: " +
                        "${amount - taken} item(s) were created."
                }
            }
        }

        override fun isFull(): Boolean = !hasRoom

        override fun isEmpty(): Boolean = storedAmount <= 0L

        override fun copyContents(destination: Array<ItemStack>) {
            val (current, count) = StorageLock.withLock { type to amount }

            destination[0] = if (current == null || count <= 0L) {
                ItemStack.empty()
            } else {
                current.createStack(min(count, current.maxStackSize.toLong()).toInt())
            }
        }

        /**
         * A barrel never feeds another barrel, and never feeds the controller that speaks for it.
         *
         * Nova connects two end points that *touch* directly, with no cable in between — which is
         * exactly how a wall of barrels is built, and how it is meant to be built, since that is how a
         * controller finds them. Without this rule the item network would look at a full barrel and an
         * empty one beside it and do the obvious thing: move a stack across, every tick, forever. The
         * wall has to be inert.
         *
         * A controller is the same storage seen a second time, so that pairing is refused for a
         * different reason and would be even worse: it would shuttle a barrel's contents into itself.
         *
         * The cost is that piping one barrel into another does not work either. That is a build nobody
         * makes, and no way of expressing "adjacent, but with a cable" exists here — the distributor
         * asks this question about a pair, not about a path.
         */
        override fun canExchangeItemsWith(other: NetworkedInventory): Boolean = when {
            other === this -> false
            other is BarrelInventory -> false
            other is BarrelController.ControllerInventory -> !other.covers(barrel)
            else -> true
        }

    }

    @TileEntityMenuClass
    inner class StorageBarrelMenu : GlobalTileEntityMenu(GuiTextures.STORAGE_BARREL) {

        private val contentsItem = ClickableItem({ contentsIcon() }, ::handleContentsClick)
        private val lockItem = ClickableItem({ lockIcon() }, { _, player, _ -> toggleLock(player) })

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . u",
                ". . . i . c . . l",
                ". . . . . . . . ."
            )
            .addIngredient('u', OpenUpgradesItem(upgradeHolder))
            .addIngredient('i', depositInventory)
            .addIngredient('c', contentsItem)
            .addIngredient('l', lockItem)
            .build()

        fun update() {
            contentsItem.notifyWindows()
            lockItem.notifyWindows()
        }

        private fun toggleLock(player: Player) {
            locked = !locked

            // unlocking an empty barrel is how a player frees it up for something else
            StorageLock.withLock {
                if (!locked && amount == 0L && type != null)
                    setType(null, NOMINAL_STACK_SIZE)
            }

            // the padlock on the front is block state; the next tick is what puts it there
            dirty = true

            player.playClickSound()
            update()
        }

        /**
         * The same click language the terminals use: a full stack on the left, half on the right,
         * shift to send it to the inventory, and a cursor that already holds something deposits it.
         */
        private fun handleContentsClick(clickType: ClickType, player: Player, click: Click) {
            if (!player.itemOnCursor.isEmpty) {
                depositCursor(player, all = !clickType.isRightClick)
                return
            }

            val current = storedType ?: return
            val stackSize = current.maxStackSize

            when {
                clickType.isShiftClick -> takeToInventory(player, current, stackSize)
                clickType == ClickType.LEFT -> takeToCursor(player, current, stackSize)
                clickType == ClickType.RIGHT -> takeToCursor(player, current, max(1, stackSize / 2))
            }
        }

        private fun depositCursor(player: Player, all: Boolean) {
            val cursor = player.itemOnCursor
            val candidate = ItemType.of(cursor) ?: return

            val offered = if (all) cursor.amount else 1
            val stored = insert(candidate, offered.toLong()).toInt()
            if (stored <= 0)
                return

            val left = cursor.amount - stored
            player.setItemOnCursor(if (left <= 0) null else candidate.createStack(left))
            update()
        }

        private fun takeToCursor(player: Player, current: ItemType, count: Int) {
            if (!player.itemOnCursor.isEmpty)
                return

            val taken = extract(current, count.toLong()).toInt()
            if (taken <= 0)
                return

            player.setItemOnCursor(current.createStack(taken))
            update()
        }

        private fun takeToInventory(player: Player, current: ItemType, count: Int) {
            val taken = extract(current, count.toLong()).toInt()
            if (taken <= 0)
                return

            val leftover = player.inventory.addItemCorrectly(current.createStack(taken))
            if (leftover > 0) {
                // put back what did not fit, and drop whatever the barrel will not take again rather
                // than deleting it
                val rejected = leftover - insert(current, leftover.toLong()).toInt()
                if (rejected > 0)
                    player.world.dropItemNaturally(player.location, current.createStack(rejected))
            }

            update()
        }

        private fun contentsIcon(): ItemBuilder {
            val (current, count) = StorageLock.withLock { type to amount }

            val builder = if (current == null) {
                ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).setName(
                    Component.translatable("menu.smartstorage.barrel.empty", NamedTextColor.GRAY)
                        .withoutPreFormatting()
                )
            } else {
                // the stack carries its own name, which is the right one and already styled
                ItemBuilder(current.createStack(max(1, min(count, current.maxStackSize.toLong()).toInt())))
            }

            val lore = ArrayList<Component>()
            lore += Component.translatable(
                "menu.smartstorage.barrel.stored",
                NamedTextColor.GRAY,
                Component.text(count, NamedTextColor.GREEN),
                Component.text(capacity, NamedTextColor.GREEN)
            ).withoutPreFormatting()
            lore += Component.translatable(
                "menu.smartstorage.barrel.stacks",
                NamedTextColor.GRAY,
                Component.text(stacks, NamedTextColor.GREEN)
            ).withoutPreFormatting()

            if (voids) {
                lore += Component.translatable("menu.smartstorage.barrel.void", NamedTextColor.RED)
                    .withoutPreFormatting()
            }

            lore += Component.translatable("menu.smartstorage.barrel.hint", NamedTextColor.DARK_GRAY)
                .withoutPreFormatting()

            builder.setLore(lore)
            return builder
        }

        /**
         * Nova's own side-config colour language: blue for a switch that is on, grey for one that is off.
         */
        private fun lockIcon(): ItemBuilder =
            (if (locked) DefaultGuiItems.BLUE_BTN else DefaultGuiItems.GRAY_BTN)
                .createClientsideItemBuilder()
                .setName(
                    Component.translatable(
                        "menu.smartstorage.barrel.lock",
                        if (locked) NamedTextColor.GREEN else NamedTextColor.GRAY,
                        Component.translatable(
                            if (locked) "menu.smartstorage.port.on" else "menu.smartstorage.port.off"
                        )
                    ).withoutPreFormatting()
                )
                .addLoreLines(
                    Component.translatable("menu.smartstorage.barrel.lock.hint", NamedTextColor.DARK_GRAY)
                        .withoutPreFormatting()
                )

    }

}
