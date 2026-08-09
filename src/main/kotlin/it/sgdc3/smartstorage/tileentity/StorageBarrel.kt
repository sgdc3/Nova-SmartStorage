package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.SmartStorage
import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.TerminalContent
import it.sgdc3.smartstorage.registry.BlockStateProperties
import it.sgdc3.smartstorage.registry.Blocks.STORAGE_BARREL
import it.sgdc3.smartstorage.registry.GuiItems
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.UpgradeTypes
import it.sgdc3.smartstorage.storage.Compaction
import it.sgdc3.smartstorage.storage.Compactions
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.storage.StorageLock
import it.sgdc3.smartstorage.util.RateLimitedError
import it.sgdc3.smartstorage.util.abbreviate
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.provider.MutableProvider
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.nova.addon.simpleupgrades.gui.OpenUpgradesItem
import xyz.xenondevs.nova.addon.simpleupgrades.storedUpgradeHolder
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.context.intention.BlockInteract
import xyz.xenondevs.nova.context.intention.BlockPlace
import xyz.xenondevs.nova.util.addItemCorrectly
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.item.ItemUtils
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.item.retrieveData
import xyz.xenondevs.nova.util.item.storeData
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.InteractionResult
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.world.format.NetworkState
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
 * Where a broken barrel's contents ride on the item it drops as.
 */
private const val CONTENTS_KEY = "barrel"

/**
 * Ticks between two looks at what is pressed against this barrel. See [TouchingInventories].
 */
private const val TOUCH_RESCAN_TICKS = 20

/**
 * The upgrades a barrel takes, in the order they are written onto its item and read back.
 *
 * Append only. The order is an index into what a dropped barrel carries, so moving an entry would make
 * every barrel already in a chest come back with the wrong upgrades in it; a new one on the end reads as
 * absent on an older item, which is what it was.
 */
private val BARREL_UPGRADES = listOf(UpgradeTypes.STORAGE, UpgradeTypes.VOID, UpgradeTypes.COMPACTING)

/**
 * How many rungs of a compaction ladder a barrel offers at once — to its own menu, to a pipe, and to a
 * [BarrelController] budgeting slots on its behalf.
 *
 * Three covers nugget, ingot and block, which is as deep as anything vanilla goes. A longer ladder from
 * another plugin offers its densest three, and everything below them is still reachable by taking the
 * ones above out first.
 *
 * One number for all three because the three have to agree: a menu showing a rung the network cannot
 * address, or a controller reserving room for a rung the barrel never offers, is a discrepancy nobody
 * would find until it stranded something.
 */
internal const val EXPOSED_TIERS = 3

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

    // Every type this barrel takes has to be listed here, not merely registered: the holder answers null
    // for anything it was not told about, and asking it for one is a null cast in the constructor — which
    // fails the whole tile entity rather than the one upgrade.
    private val upgradeHolder = storedUpgradeHolder(UpgradeTypes.STORAGE, UpgradeTypes.VOID, UpgradeTypes.COMPACTING)

    /**
     * Whether this barrel burns what it has no room for.
     *
     * Read on every insert, so it is cached rather than walked: `getValue` goes through a map and a
     * config list, and the network asks a full wall of barrels this question several times a second.
     * The provider below keeps it current.
     */
    @Volatile
    private var voids: Boolean = upgradeHolder.getValue(UpgradeTypes.VOID) > 0

    /**
     * Whether this barrel keeps what it holds in the densest form the server has a recipe for.
     *
     * Cached beside [voids] and for the same reason: the insert path asks on every offer.
     */
    @Volatile
    private var compacts: Boolean = upgradeHolder.getValue(UpgradeTypes.COMPACTING) > 0

    /**
     * Material that has arrived but does not yet amount to one whole unit of what the barrel stores,
     * counted in the smallest tier of its ladder — so up to eighty for iron.
     *
     * It is a number rather than a second stored type because a barrel holds one type and this must not
     * become an exception to that. Nothing outside the barrel ever sees it: the network is told what the
     * barrel holds, and eight nuggets are not a tenth of an iron block anybody can ask for.
     */
    private val remainderValue = storedValue("remainder") { 0L }
    private var remainder: Long = max(0L, remainderValue.get())

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

    /**
     * What this barrel is touching, so that it can decline all of it. See [TouchingInventories].
     */
    private val touching = TouchingInventories()

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

        // Taking the Compacting Upgrade out leaves the remainder with nowhere to live — it is a part of
        // a block, and a barrel without the upgrade holds whole things only. It goes back to the player
        // as the largest pieces it makes, which is what they would have got by crafting it down.
        upgradeHolder.getValueProvider(UpgradeTypes.COMPACTING).subscribe { level ->
            val on = level > 0
            if (compacts && !on)
                releaseRemainder()

            compacts = on

            // Putting the upgrade in acts on what is already there, rather than only on what arrives
            // next: a barrel of two thousand ingots should become a barrel of blocks the moment it is
            // told to, which is what somebody installing it is asking for.
            if (on) {
                StorageLock.withLock {
                    type?.let(Compactions::of)?.let(::compactExisting)
                }
            }

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

    /**
     * Whether there is anything in here at all, the remainder included.
     *
     * Deliberately not `storedAmount > 0`. A compacting barrel holding nine iron nuggets holds *no whole
     * blocks*, and calling that empty is what stranded them: Nova's distributor gives up on a provider
     * that says it is empty before it looks at a single slot, so a pipe that drained a barrel of blocks
     * down to a part-block left that part behind and never came back for it.
     */
    val hasContents: Boolean
        get() = StorageLock.withLock { amount > 0L || remainder > 0L }

    /**
     * What this barrel offers as addressable stock, densest first: every rung of its ladder, or its one
     * stored type when it does not compact.
     *
     * The shape both the item network and a [BarrelController] address it by, so there is one answer to
     * "what can be taken out of here" rather than two that can drift.
     */
    fun offers(): List<Pair<ItemType, Long>> = densities().take(EXPOSED_TIERS).ifEmpty {
        val current = storedType
        val count = storedAmount
        if (current == null || count <= 0L) emptyList() else listOf(current to count)
    }

    fun holds(candidate: ItemType): Boolean =
        StorageLock.withLock { countOf(candidate) > 0L }

    /**
     * How much of [candidate] this barrel could give up, counted in [candidate].
     *
     * A compacting barrel answers for every rung of its ladder: a barrel of fifteen iron blocks can
     * hand over fifteen blocks, or a hundred and thirty-five ingots, or twelve hundred and fifteen
     * nuggets. **Those are the same iron three times over, not three stocks** — anything summing across
     * rungs is counting one barrel three times, which is why [collectInto] still reports only what the
     * barrel stores and why the aggregates are built from that rather than from here.
     *
     * Callers may hold [StorageLock]; it is reentrant.
     */
    fun countOf(candidate: ItemType): Long = StorageLock.withLock {
        val current = type ?: return@withLock 0L
        val ladder = if (compacts) Compactions.of(candidate) else null

        // A barrel that is not compacting has one type and one number, and that is the whole answer.
        if (ladder == null)
            return@withLock if (current == candidate) amount else 0L

        // Everything else goes through the units, the same-item case included. Answering `amount` for
        // it would be right only while the barrel stores the top of its ladder: one still holding
        // ingots — loaded from a save older than its own upgrade — would say two when [breakdown] says
        // seven, and the smaller of two disagreeing answers is what the network believes.
        val held = ladder.unitsOf(current)
        val wanted = ladder.unitsOf(candidate)
        if (held <= 0L || wanted <= 0L) 0L else (amount * held + remainder) / wanted
    }

    /**
     * What this barrel holds written out along its compaction ladder, densest first, or empty if it is
     * not compacting anything.
     *
     * The readout every menu showing a compacting barrel wants: a count of blocks alone is true but
     * unhelpful, since the barrel is also holding the ingots and nuggets that have not made one yet and
     * a player who put them there will look for them.
     *
     * **These do not add up.** One iron block is one block *and* nine ingots *and* eighty-one nuggets,
     * all three true and all three the same block. See [pieces] for the list that does add up, and be
     * sure which one the caller wants.
     */
    fun densities(): List<Pair<ItemType, Long>> {
        if (!compacts)
            return emptyList()

        return StorageLock.withLock {
            val current = type ?: return@withLock emptyList()
            val ladder = Compactions.of(current) ?: return@withLock emptyList()
            val worth = ladder.unitsOf(current)
            if (worth <= 0L) emptyList() else ladder.atEachTier(amount * worth + remainder)
        }
    }

    /**
     * What this barrel holds split into the largest pieces that hold it — fifteen blocks, two ingots
     * and one nugget — densest first.
     *
     * The counterpart to [densities], and the one that **adds up**: this is a decomposition, so it is
     * what to print where there is nothing to click and only a total to read. A plain barrel answers
     * with its one type and its one number, so callers do not need a second branch for it.
     */
    fun pieces(): List<Pair<ItemType, Long>> = StorageLock.withLock {
        val current = type ?: return@withLock emptyList()
        val ladder = if (compacts) Compactions.of(current) else null

        if (ladder == null)
            return@withLock if (amount > 0L) listOf(current to amount) else emptyList()

        val worth = ladder.unitsOf(current)
        if (worth <= 0L) emptyList() else ladder.breakdown(amount * worth + remainder)
    }

    /**
     * How many of [tier] would fill this barrel, for a rung of the ladder it is compacting along: a
     * barrel that holds two thousand iron blocks is one that holds a hundred and sixty thousand nuggets.
     *
     * [capacity] for anything that is not on the ladder, which is every barrel without the upgrade.
     */
    fun capacityOf(tier: ItemType): Long = StorageLock.withLock {
        val current = type
        if (current == null || tier == current)
            return@withLock capacity

        val ladder = (if (compacts) Compactions.of(current) else null) ?: return@withLock capacity
        val perCurrent = ladder.unitsOf(current)
        val perTier = ladder.unitsOf(tier)
        if (perCurrent <= 0L || perTier <= 0L) capacity else capacity * perCurrent / perTier
    }

    /**
     * Whether this barrel would take [candidate] if it were offered right now.
     *
     * The same rule [insertCounted] applies, asked ahead of time rather than discovered by trying —
     * which is what the controller's drop-off slot needs to turn an item away before it lands in a slot
     * nothing can then empty. Kept here, next to the insert it mirrors, because two copies of "would
     * this fit" would eventually disagree and the disagreement would be invisible.
     */
    fun accepts(candidate: ItemType): Boolean = StorageLock.withLock {
        val current = type
        // With a Compacting Upgrade in it the barrel answers for the whole ladder: a barrel of iron
        // blocks is exactly where an iron nugget belongs, and so is one still holding ingots because
        // it was filled before the upgrade went in.
        val ladder = if (compacts) Compactions.of(candidate) else null

        when {
            // locked onto nothing is locked shut
            current == null -> !locked && hasRoom
            ladder != null -> ladder.unitsOf(current) > 0L && hasRoom
            current != candidate -> false
            else -> hasRoom
        }
    }

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
            // A barrel locked onto nothing is locked shut. Locking says "this one is for that item",
            // and with no item named it has named nothing — so taking on the next thing offered would
            // be the opposite of what the switch was thrown for. Unlock it to open it again.
            if (type == null && locked)
                return@withLock Inserted.NONE

            val ladder = if (compacts) Compactions.of(candidate) else null
            if (ladder != null)
                return@withLock insertCompacted(ladder, candidate, count)

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
     * [insertCounted] for a barrel with a Compacting Upgrade in it, offered something on a ladder.
     *
     * Everything is counted in the ladder's smallest tier while the arithmetic happens — nuggets, for
     * iron — because that is the only unit in which nine nuggets, one ingot and a ninth of a block are
     * the same quantity. What comes out the other side is as many whole blocks as those units make, and
     * the [remainder] keeps what is left over.
     *
     * The barrel adopts the *top* of the ladder whatever it was offered, which is the whole point: feed
     * an empty compacting barrel a nugget and it becomes a barrel of iron blocks, not one of nuggets.
     *
     * Callers hold [StorageLock].
     */
    private fun insertCompacted(ladder: Compaction, candidate: ItemType, count: Long): Inserted {
        val top = ladder.top

        // A barrel that was already holding ingots when the upgrade went in is still a barrel of
        // ingots, and refusing everything until somebody empties it is not an answer. It climbs to the
        // top of its own ladder instead — which is what the upgrade was installed to do.
        compactExisting(ladder)

        val current = type
        if (current != null && current != top)
            return Inserted.NONE

        val perTop = ladder.unitsOf(top)
        val perCandidate = ladder.unitsOf(candidate)
        if (perTop <= 0L || perCandidate <= 0L)
            return Inserted.NONE

        val topStackSize = top.maxStackSize
        val held = amount * perTop + remainder
        // the remainder rides above the last whole unit, so the barrel holds a shade more than its
        // capacity in blocks — never enough to round up to another one
        val room = stacks.toLong() * topStackSize * perTop + (perTop - 1) - held
        val taken = min(count, room / perCandidate)

        if (taken <= 0L)
            return if (voids && current != null) Inserted(count, 0L) else Inserted.NONE

        val units = held + taken * perCandidate
        if (current == null)
            setType(top, topStackSize)
        setAmount(units / perTop)
        setRemainder(units % perTop)

        return Inserted(if (voids) count else taken, taken)
    }

    /**
     * Climbs whatever is already in the barrel to the top of [ladder], if it is not there yet.
     *
     * The count only ever goes down — nine ingots become one block — so this cannot overflow a barrel
     * that already fitted. Callers hold [StorageLock].
     */
    private fun compactExisting(ladder: Compaction) {
        val current = type ?: return
        val top = ladder.top
        if (current == top || ladder.unitsOf(current) <= 0L)
            return

        val perTop = ladder.unitsOf(top)
        val units = amount * ladder.unitsOf(current) + remainder

        setType(top, top.maxStackSize)
        setAmount(units / perTop)
        setRemainder(units % perTop)
    }

    /**
     * Undoes an [insert] that the caller could not commit to, in the units it was made in.
     *
     * A plain barrel can undo one by extracting the same item, which is what every caller used to do.
     * A compacting barrel cannot: it was given ingots and is holding blocks, so asking it for ingots
     * back gets nothing, and the items would then exist both in the barrel and wherever the caller
     * failed to put them. That is the shape of every duplication in this addon, so it gets its own
     * path rather than a comment asking people to remember.
     */
    fun retract(candidate: ItemType, count: Long): Long {
        if (count <= 0L)
            return 0L

        return StorageLock.withLock {
            val ladder = if (compacts) Compactions.of(candidate) else null
            if (ladder == null || type != ladder.top)
                return@withLock extract(candidate, count)

            val perTop = ladder.unitsOf(ladder.top)
            val perCandidate = ladder.unitsOf(candidate)
            if (perTop <= 0L || perCandidate <= 0L)
                return@withLock 0L

            val held = amount * perTop + remainder
            // answered in the units it was asked in, so a caller undoing an insert of nine ingots is
            // told nine and not one
            val undone = min(count, held / perCandidate)
            if (undone <= 0L)
                return@withLock 0L

            val units = held - undone * perCandidate
            setAmount(units / perTop)
            setRemainder(units % perTop)

            if (units == 0L && !locked)
                setType(null, NOMINAL_STACK_SIZE)

            undone
        }
    }

    /**
     * Hands back what is in the [remainder] as the largest pieces that will hold it — forty iron
     * nuggets come out as four ingots and four nuggets — and clears it.
     *
     * Main thread only: it spawns items. Called when the Compacting Upgrade comes out, because without
     * it the barrel has nowhere to keep a part of a block.
     */
    private fun releaseRemainder() {
        if (!isEnabled)
            return

        // The pieces are worked out before the counter is cleared, and the counter is only cleared once
        // there are pieces to hand over. Clearing first and then failing to find a ladder — which is
        // what a barrel whose type went missing would do — would be a silent deletion.
        val pieces = StorageLock.withLock {
            val held = remainder
            if (held <= 0L)
                return@withLock emptyList()

            val ladder = type?.let(Compactions::of) ?: return@withLock emptyList()
            setRemainder(0L)
            ladder.split(held)
        }

        for (stack in pieces)
            pos.world.dropItemNaturally(pos.location.add(0.5, 1.0, 0.5), stack)
    }

    private fun setRemainder(next: Long) {
        remainder = next
        remainderValue.set(next)
        dirty = true
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
            val current = type ?: return@withLock 0L
            // the same-item case included, for the reason [countOf] gives: the two have to agree, and
            // the units are the answer that is right whichever rung the barrel happens to be storing
            val ladder = if (compacts) Compactions.of(candidate) else null

            if (ladder != null) {
                // A compacting barrel breaks its blocks back down on the way out: asked for ingots it
                // gives ingots, and what it holds is only ever "iron" at one density or another. The
                // conversion is the same arithmetic as going in, run the other way.
                val held = ladder.unitsOf(current)
                val wanted = ladder.unitsOf(candidate)
                if (held <= 0L || wanted <= 0L)
                    return@withLock 0L

                val units = amount * held + remainder
                val taken = min(count, units / wanted)
                if (taken <= 0L)
                    return@withLock 0L

                val left = units - taken * wanted
                setAmount(left / held)
                setRemainder(left % held)
                if (left == 0L && !locked)
                    setType(null, NOMINAL_STACK_SIZE)

                return@withLock taken
            }

            if (current != candidate)
                return@withLock 0L

            val taken = min(count, amount)
            if (taken <= 0L)
                return@withLock 0L

            setAmount(amount - taken)
            // an unlocked barrel forgets what it held once it is empty, so it can be reused without
            // being emptied by hand; locking is how a player says "this one is for iron, always"
            if (amount == 0L && remainder == 0L && !locked)
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
     * Hands back whatever no longer fits. Main thread only — it spawns items.
     *
     * Taking a Storage Upgrade out is the obvious way to end up over capacity, and it subscribes to
     * exactly that. Placing one down is the other: upgrades drop as items of their own while the contents
     * ride on the barrel, so a 256-stack barrel put back up bare is a 32-stack barrel holding eight times
     * what it should. Running this from the tick as well makes the invariant hold however it was broken,
     * including by a save older than the rule, rather than only for the cause anyone thought of first.
     *
     * Costs one comparison when there is nothing to do, and only runs on a tick where the contents
     * changed.
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

    override suspend fun handleNetworkLoaded(state: NetworkState) = syncTouchingFaces(state)

    override suspend fun handleNetworkUpdate(state: NetworkState) = syncTouchingFaces(state)

    /**
     * A barrel is passive storage: it moves items when a pipe, a connector or a player asks it to, and
     * at no other time. Nova joins two end points that *touch* into one network with no cable between
     * them, so a chest set beside a barrel would otherwise fill it, every tick, for nobody.
     */
    private suspend fun syncTouchingFaces(state: NetworkState) {
        restrictItemFaces(state, pos, itemHolder, extractOnlyFromBelow = true)
        touching.refresh(state, pos)
    }

    override fun handleTick() {
        drainDeposit()

        // On a timer rather than only when Nova says the networks moved, and it has to be: closing a
        // face is what stops the chest beside this barrel from filling it, and a closed face no longer
        // receives the update that would say the chest is gone. See closeTouchingItemFaces.
        if (serverTick % TOUCH_RESCAN_TICKS == 0)
            NetworkManager.queueWrite(pos.chunkPos, ::syncTouchingFaces)

        if (!dirty)
            return
        dirty = false

        ejectOverflow()
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
        super.handleBreak(ctx)
    }

    /**
     * Restores a barrel placed from an item that was carrying contents. See [getDrops].
     *
     * Nothing here is conditional on the barrel being empty, because a freshly placed one always is: a
     * tile entity is built from its own stored data, and a barrel that has never been placed has none.
     */
    override fun handlePlace(ctx: Context<BlockPlace>) {
        super.handlePlace(ctx)

        val carried = ctx[BlockPlace.BLOCK_ITEM_STACK]
            ?.retrieveData<Compound>(SmartStorage, CONTENTS_KEY)
            ?: return

        val carriedStack: ItemStack? = carried["type"]
        val stored = ItemType.of(carriedStack)
        val count: Long = carried["amount"] ?: 0L

        // Before the contents, so that capacity is already what it was when the amount lands and the
        // overflow sweep in handleTick has nothing to hand back.
        val levels: ArrayList<Int>? = carried["upgrades"]
        if (levels != null) {
            for ((index, type) in BARREL_UPGRADES.withIndex()) {
                val level = levels.getOrNull(index) ?: 0
                if (level > 0)
                    upgradeHolder.addUpgrade(type, level)
            }
        }

        StorageLock.withLock {
            setType(stored, stored?.maxStackSize ?: NOMINAL_STACK_SIZE)
            setAmount(if (stored == null) 0L else max(0L, count))
            setRemainder(if (stored == null) 0L else max(0L, carried["remainder"] ?: 0L))
        }

        locked = carried["locked"] ?: false
        // the padlock and the front are block state, which the next tick is what puts there
        dirty = true
    }

    /**
     * The barrel travels full.
     *
     * Everything it holds is written onto the item it drops as — contents, remainder, lock and upgrades
     * alike — so that taking one down and putting it back up is a *move* rather than an emptying. A wall
     * can be dismantled into a stack of barrels and rebuilt somewhere else with nothing on the floor in
     * between, which is the whole reason to keep thousands of one item in a block rather than in a chest.
     *
     * **Nothing is ever dropped loose.** The contents leave on the barrel or they do not leave: a break
     * that does not earn the block — the wrong tool, a cancelled drop, creative — takes what is inside
     * with it, exactly as it does for any other machine with an inventory. Spilling the contents of a
     * block that did not itself drop is the one behaviour this must not have, because it reads as the
     * barrel being destroyed *and* looted.
     *
     * Deliberately free of side effects on the barrel itself. Nova calls this from `BlockUtils.getDrops`
     * as well as from a real break, and that is a plain question — `BlockManager` exposes it to any
     * plugin wanting to know what a block would drop — so a barrel that emptied itself when asked would
     * lose everything to something merely looking at it. Nothing here needs to: the contents are copied
     * onto a fresh item, and the barrel they were copied from is about to stop existing.
     */
    override fun getDrops(includeSelf: Boolean): List<ItemStack> {
        val drops = ArrayList(super.getDrops(includeSelf))

        // The upgrades ride on the barrel or they do not leave at all, so they come out of the list
        // first and whether or not the barrel itself is dropping.
        //
        // Nova runs an upgrade holder's drop provider unconditionally — `includeSelf` only governs the
        // block — and a creative break is exactly the case where that is false. Left alone it deletes
        // the barrel and leaves three upgrades lying where it stood, which is neither what breaking a
        // block in creative does nor what breaking it in survival should do.
        val upgrades = upgradeHolder.getUpgradeItems()
        // Matched one stack at a time against what the holder actually holds rather than by item type,
        // so an upgrade a player happens to have parked in the drop-off slot still falls out as it should.
        for (stack in upgrades) {
            val at = drops.indexOfFirst { it.novaItem == stack.novaItem && it.amount == stack.amount }
            if (at >= 0)
                drops.removeAt(at)
        }

        val levels = ArrayList<Int>(BARREL_UPGRADES.size)
        for (type in BARREL_UPGRADES)
            levels += upgradeHolder.getLevel(type)

        // `block.item` rather than the registry constant: it is the expression `TileEntity.getDrops`
        // itself builds the stack from, so what is looked for here cannot drift from what was made.
        // Absent means the barrel is not dropping at all, and then neither is anything it carried.
        val self = drops.firstOrNull { it.novaItem == block.item } ?: return drops

        val (current, count, wasLocked) = StorageLock.withLock { Triple(type, amount, locked) }
        val carried = Compound()

        if (current != null) {
            carried["type"] = current.stack
            carried["amount"] = count
            // the part of a block that is not one yet; it has nowhere else to go and losing it on every
            // move would make a compacting barrel bleed a few nuggets each time it was picked up
            carried["remainder"] = StorageLock.withLock { remainder }
        }

        if (wasLocked)
            carried["locked"] = true

        // A 256-stack barrel put back down should still be one: making the player pick three upgrades
        // off the floor and refit them is busywork, and it was also the thing that could leave a full
        // barrel over its own capacity the moment it was replaced.
        if (levels.any { it > 0 })
            carried["upgrades"] = levels

        // Nothing to carry is a barrel that came out of the ground the way it went in, and it should
        // stack with every other one.
        if (carried.isEmpty())
            return drops

        // Two barrels holding the same thing would otherwise be the same item, and a stack of them would
        // hold one set of contents between them while handing it out once per barrel placed. Carrying
        // the barrel's own identity keeps every full one distinct without touching the stack size.
        carried["source"] = uuid.toString()

        self.storeData(SmartStorage, CONTENTS_KEY, carried)
        describe(self, current, pieces(), wasLocked, upgrades)

        return drops
    }

    /**
     * Writes what a dropped barrel holds onto the item — into its name as well as its lore, since an
     * item has no front to print it on and a chest full of identical "Storage Barrel"s is no better
     * than a chest full of unlabelled boxes.
     *
     * Translatable components rather than rendered strings: they are baked once, here, but the client
     * is what resolves them, so a barrel picked up on one language's client and dropped for another
     * reads correctly for both. Baking is honest — an item's contents cannot change while it is an item.
     */
    private fun describe(
        itemStack: ItemStack,
        type: ItemType?,
        pieces: List<Pair<ItemType, Long>>,
        locked: Boolean,
        upgrades: List<ItemStack>
    ) {
        // The densest piece actually in there, rather than the type the barrel files it under. A
        // compacting barrel holding five ingots stores them as iron *blocks*, and an item calling itself
        // that while containing no block at all is the same lie the barrel's front used to tell. An
        // empty barrel that is locked has no pieces and falls back to what it is reserved for, which is
        // the one thing worth saying about it.
        val label = pieces.firstOrNull()?.first ?: type
        if (label != null) {
            val named = Component.translatable(
                "item.smartstorage.storage_barrel.named",
                ItemUtils.getName(itemStack),
                ItemUtils.getName(label.stack)
            ).withoutPreFormatting()

            itemStack.editMeta { it.displayName(named) }
        }

        val lore = ArrayList<Component>()

        // Every piece rather than only the densest: a barrel holding fifteen blocks and two ingots is
        // holding both, and a line that says fifteen blocks quietly loses the two.
        for ((piece, held) in pieces) {
            lore += Component.text()
                .color(NamedTextColor.GRAY)
                .append(Component.text("$held× "))
                .append(ItemUtils.getName(piece.stack))
                .build()
                .withoutPreFormatting()
        }

        // Named rather than merely carried: an upgrade that is inside the barrel instead of on the floor
        // is an upgrade the player cannot see, and one they will assume was eaten.
        for (stack in upgrades) {
            lore += Component.text()
                .color(NamedTextColor.DARK_GRAY)
                .append(Component.text("${stack.amount}× "))
                .append(ItemUtils.getName(stack))
                .build()
                .withoutPreFormatting()
        }

        if (locked) {
            lore += Component.translatable("item.smartstorage.storage_barrel.lore.locked", NamedTextColor.DARK_GRAY)
                .withoutPreFormatting()
        }

        itemStack.lore(lore)
    }

    /**
     * Puts the contents on the front: one cell per rung of the ladder, each with its own icon and count.
     *
     * A barrel that compacts nothing has one, which is the front it always had. A compacting one has the
     * densest across the top and the lesser ones beneath — so an iron barrel says *block, ingot, nugget*
     * in pictures rather than making a player work it out from three bare numbers.
     *
     * A barrel holding less than one block leads with ingots rather than "zero blocks", which is what
     * stopped one with material in it from reading as empty.
     *
     * The counts are abbreviated because a block face has room for four characters and eighteen thousand
     * nuggets is five. The menu is where the exact figure lives.
     */
    private fun refreshFace() {
        val face = this.face ?: return

        val rungs = StorageLock.withLock {
            densities().take(EXPOSED_TIERS).ifEmpty { type?.let { listOf(it to amount) } ?: emptyList() }
        }

        face.update(
            rungs.map { (tier, held) ->
                FaceCell(
                    tier.createStack(1),
                    Component.text(abbreviate(held), if (held > 0L) NamedTextColor.WHITE else NamedTextColor.GRAY)
                )
            }
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
            retract(candidate, inserted.kept)
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
     * The barrel as Nova's item network sees it: one slot per rung it offers, each holding one stack's
     * worth of whatever is inside, however many more of them there really are.
     *
     * [EXPOSED_TIERS] slots rather than one, so that a compacting barrel can be asked for its ingots
     * and not only for its blocks — the same thing a [BarrelController] does for a whole wall, and a
     * pipe pressed straight against a barrel should not get less. A barrel that compacts nothing fills
     * the first and leaves the rest empty.
     *
     * **The slots are one stock seen several ways.** Nothing may add them up, and nothing does:
     * [canTake] and [take] read the barrel live rather than the snapshot, so the moment blocks leave
     * through one slot the others answer smaller. Nova's distributor asks [canTake] before every single
     * transfer, which is what makes that enough.
     *
     * The counts are capped at a stack, which costs nothing: the distributor moves at most a stack per
     * transfer anyway, and it takes a fresh snapshot every tick.
     */
    internal inner class BarrelInventory : NetworkedInventory {

        val barrel: StorageBarrel
            get() = this@StorageBarrel

        /**
         * What each snapshot slot referred to, written by [copyContents].
         */
        @Volatile
        private var slots: Array<ItemType?> = arrayOfNulls(EXPOSED_TIERS)

        override val uuid: UUID
            get() = this@StorageBarrel.uuid

        override val size: Int
            get() = EXPOSED_TIERS

        override fun add(itemStack: ItemStack, amount: Int): Int {
            val candidate = ItemType.of(itemStack) ?: return amount
            return amount - insert(candidate, amount.toLong()).toInt()
        }

        /**
         * A promise, so it is asked on the same terms [extract] answers on — `isEnabled` included. A
         * barrel whose chunk went in between would otherwise say yes and then give nothing, and the
         * distributor has already handed the items over by the time it finds out.
         */
        override fun canTake(slot: Int, amount: Int): Boolean {
            val type = slots.getOrNull(slot) ?: return false
            return isEnabled && countOf(type) >= amount
        }

        /**
         * A short take here is items created out of nothing, and there is no way to say so — see
         * [NetworkView.take][it.sgdc3.smartstorage.network.NetworkView.take] for why that is structurally
         * impossible today and why it is checked anyway.
         */
        override fun take(slot: Int, amount: Int) {
            val type = slots.getOrNull(slot) ?: return
            val taken = extract(type, amount.toLong())

            if (taken < amount) {
                SHORTFALL.log {
                    "Barrel at $pos handed out $amount× $type but only had $taken: " +
                        "${amount - taken} item(s) were created."
                }
            }
        }

        override fun isFull(): Boolean = !hasRoom

        // hasContents, not storedAmount: a barrel down to a part of a block still has something in it,
        // and the distributor skips an inventory that calls itself empty without reading a slot
        override fun isEmpty(): Boolean = !hasContents

        override fun copyContents(destination: Array<ItemStack>) {
            val slots = arrayOfNulls<ItemType>(EXPOSED_TIERS)
            var at = 0

            for ((tier, held) in offers()) {
                if (at >= EXPOSED_TIERS)
                    break

                slots[at] = tier
                destination[at] = tier.createStack(min(held, tier.maxStackSize.toLong()).toInt())
                at++
            }

            while (at < EXPOSED_TIERS) {
                destination[at] = ItemStack.empty()
                at++
            }

            this.slots = slots
        }

        /**
         * A barrel trades with nothing it is merely touching.
         *
         * Nova connects two end points that *touch* directly, with no cable in between — which is
         * exactly how a wall of barrels is built, and how it is meant to be built, since that is how a
         * controller finds them. Left alone the item network looks at a full barrel and an empty chest
         * beside it and does the obvious thing: moves a stack across, every tick, forever, in whichever
         * direction the numbers happen to point. Nobody placed either block asking for that.
         *
         * This started as the narrower rule — no barrel feeds another barrel — because two barrels was
         * the pairing anyone had noticed. A chest on the next block is the same pathology with a
         * different block on the other side of it, and so is a hopper, and so is a machine. A barrel is
         * passive storage: it moves items when a pipe, a connector or a player asks it to, and at no
         * other time. See [TouchingInventories].
         *
         * A controller is kept out separately, because it is not merely adjacent — it is the same
         * storage seen a second time, and a controller reached through a cable would still be shuttling
         * a barrel's contents into itself.
         *
         * The cost is that a hopper or a machine set straight against a barrel no longer feeds it; one
         * segment of cable between them does. No way of expressing "adjacent, but with a cable" exists
         * here — the distributor asks this question about a pair, not about a path — so the choice is
         * which way to be wrong, and a barrel that quietly empties itself into the chest next door is
         * the worse one.
         */
        override fun canExchangeItemsWith(other: NetworkedInventory): Boolean = when {
            other === this -> false
            other is BarrelInventory -> false
            other is BarrelController.ControllerInventory -> !other.covers(barrel)
            // and nothing else it is merely pressed against either: a barrel beside a chest is the same
            // shuttle as a barrel beside a barrel, and a barrel is passive storage in both directions
            other in touching -> false
            else -> true
        }

    }

    @TileEntityMenuClass
    inner class StorageBarrelMenu : GlobalTileEntityMenu(GuiTextures.STORAGE_BARREL) {

        /**
         * One slot per rung of the barrel's compaction ladder, densest first.
         *
         * A plain barrel fills the first and leaves the rest blank, which is the menu it always had. A
         * compacting one writes the same holding out at every density it reaches — one block, nine
         * ingots, eighty-one nuggets — and every rung is clickable, so getting ingots out of a barrel
         * of blocks is a click rather than a trip through a pipe and a crafting grid.
         */
        private val tierItems = List(EXPOSED_TIERS) { position ->
            ClickableItem(
                { tierIcon(position) },
                { clickType, player, _ -> handleTierClick(position, clickType, player) }
            )
        }

        private val lockItem = ClickableItem({ lockIcon() }, { _, player, _ -> toggleLock(player) })

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . u",
                // c, d and e are the rungs, densest first. Everything the barrel holds sits left of
                // centre so that three of them still leave a gap before the lock button.
                ". . i . c d e . l",
                ". . . . . . . . ."
            )
            .addIngredient('u', OpenUpgradesItem(upgradeHolder))
            // the same arrow the terminals draw in theirs: an empty slot that takes what you drop on it
            // has to be told apart from a panel that does nothing, and it is the same slot everywhere
            .addIngredient('i', depositInventory, TerminalContent.depositBackground())
            .addIngredient('c', tierItems[0])
            .addIngredient('d', tierItems[1])
            .addIngredient('e', tierItems[2])
            .addIngredient('l', lockItem)
            .build()

        fun update() {
            for (item in tierItems)
                item.notifyWindows()
            lockItem.notifyWindows()
        }

        private fun toggleLock(player: Player) {
            locked = !locked

            // Unlocking an empty barrel is how a player frees it up for something else — and a
            // compacting barrel is only empty once its remainder is gone too.
            //
            // Without that second test a barrel showing zero blocks but holding, say, five ingots' worth
            // forgot what it was the moment it was unlocked. The units stayed: nothing could ask for an
            // item the barrel no longer said it held, and the next thing stored in it inherited them —
            // so a fresh copper nugget landed on top of forty-five iron units and the barrel went on
            // reading zero. Unlocking it again looked like the thing that eventually fixed it.
            StorageLock.withLock {
                if (!locked && amount == 0L && remainder == 0L && type != null)
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
         *
         * Which item comes out is what [position] decides. Every slot deposits alike, blank ones
         * included, since what the barrel takes does not depend on where you dropped it.
         */
        private fun handleTierClick(position: Int, clickType: ClickType, player: Player) {
            if (!player.itemOnCursor.isEmpty) {
                depositCursor(player, all = !clickType.isRightClick)
                return
            }

            val current = tierAt(position) ?: return
            val stackSize = current.maxStackSize

            when {
                clickType.isShiftClick -> takeToInventory(player, current, stackSize)
                clickType == ClickType.LEFT -> takeToCursor(player, current, stackSize)
                clickType == ClickType.RIGHT -> takeToCursor(player, current, max(1, stackSize / 2))
            }
        }

        /**
         * The item shown on [rung], or null if that rung has nothing on it.
         *
         * Read fresh on every click rather than kept beside the icon: what the barrel holds moves under
         * an open menu, and a rung that has run out between the draw and the click has to come back as
         * nothing rather than as the item that used to be there.
         */
        private fun tierAt(position: Int): ItemType? {
            val rungs = shownRungs()
            if (rungs.isEmpty())
                return if (position == offsetFor(1)) storedType else null

            return rungs.getOrNull(position - offsetFor(rungs.size))?.first
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

        /**
         * What [rung] draws: its density of what the barrel holds, or nothing when the ladder does not
         * reach that far.
         *
         * A rung that has come to nothing is left blank rather than shown as a zero. The barrel is one
         * holding, and three lines of which two say none is a worse answer than two lines.
         */
        private fun tierIcon(position: Int): ItemProvider {
            val rungs = shownRungs()
            if (rungs.isEmpty())
                return if (position == offsetFor(1)) contentsIcon() else ItemProvider.EMPTY

            val offset = offsetFor(rungs.size)
            val (tier, held) = rungs.getOrNull(position - offset) ?: return ItemProvider.EMPTY
            // the stack carries its own name, which is the right one and already styled
            return ItemBuilder(tier.createStack(max(1, min(held, tier.maxStackSize.toLong()).toInt())))
                .setLore(tierLore(tier, held, primary = position == offset))
        }

        /**
         * The rungs the three slots have room for, densest first.
         *
         * A ladder deeper than three loses its smallest rungs here rather than growing the menu: they
         * are still reachable, by taking the ones above out first.
         */
        private fun shownRungs(): List<Pair<ItemType, Long>> = densities().take(EXPOSED_TIERS)

        /**
         * Where the first rung sits, so that fewer than [EXPOSED_TIERS] of them come out centred instead
         * of pushed against the left edge.
         *
         * One line lands in the middle slot — which is every barrel that is not compacting anything, so
         * it is the ordinary case rather than the exception.
         */
        private fun offsetFor(count: Int): Int = (EXPOSED_TIERS - count) / 2

        /**
         * The single line a barrel that is not compacting anything draws, and the empty pane a barrel
         * with nothing in it draws whether it compacts or not.
         */
        private fun contentsIcon(): ItemBuilder {
            val (current, count) = StorageLock.withLock { type to amount }

            if (current == null) {
                return ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .setName(
                        Component.translatable("menu.smartstorage.barrel.empty", NamedTextColor.GRAY)
                            .withoutPreFormatting()
                    )
                    .setLore(tierLore(null, 0L, primary = true))
            }

            return ItemBuilder(current.createStack(max(1, min(count, current.maxStackSize.toLong()).toInt())))
                .setLore(tierLore(current, count, primary = true))
        }

        /**
         * How much of [tier] is in there and how much of it would fit, then — on the first rung only —
         * what is true of the barrel rather than of that one density.
         *
         * Both figures are priced in [tier], so the ingot line of a barrel of iron blocks reads *5 /
         * 18432* rather than 5 out of a capacity counted in blocks it does not hold.
         */
        private fun tierLore(tier: ItemType?, held: Long, primary: Boolean): List<Component> {
            val lore = ArrayList<Component>()

            lore += Component.translatable(
                "menu.smartstorage.barrel.stored",
                NamedTextColor.GRAY,
                Component.text(held, NamedTextColor.GREEN),
                Component.text(if (tier == null) capacity else capacityOf(tier), NamedTextColor.GREEN)
            ).withoutPreFormatting()

            if (primary) {
                lore += Component.translatable(
                    "menu.smartstorage.barrel.stacks",
                    NamedTextColor.GRAY,
                    Component.text(stacks, NamedTextColor.GREEN)
                ).withoutPreFormatting()

                if (compacts) {
                    lore += Component.translatable("menu.smartstorage.barrel.compacting", NamedTextColor.AQUA)
                        .withoutPreFormatting()
                }

                if (voids) {
                    lore += Component.translatable("menu.smartstorage.barrel.void", NamedTextColor.RED)
                        .withoutPreFormatting()
                }
            }

            lore += Component.translatable("menu.smartstorage.barrel.hint", NamedTextColor.DARK_GRAY)
                .withoutPreFormatting()

            return lore
        }

        /**
         * Nova's own side-config colour language: blue for a switch that is on, grey for one that is off.
         */
        private fun lockIcon(): ItemBuilder =
            (if (locked) GuiItems.LOCK_ON else GuiItems.LOCK_OFF)
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
