package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.TerminalContent
import it.sgdc3.smartstorage.gui.networkStatusIcon
import it.sgdc3.smartstorage.network.StorageEndPoint
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.registry.TERMINAL_REFRESH_TICKS
import it.sgdc3.smartstorage.storage.ItemType
import it.sgdc3.smartstorage.storage.SortMode
import it.sgdc3.smartstorage.storage.StorageEntry
import org.bukkit.entity.Player
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.provider.MutableProvider
import xyz.xenondevs.commons.provider.Provider
import xyz.xenondevs.invui.dsl.IngredientsDsl
import xyz.xenondevs.invui.gui.ScrollGui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.config.node
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import kotlin.math.max

private const val DEPOSIT_SLOTS = 8

/**
 * Shared behaviour of the terminals that are *blocks*: the deposit slots, and being on the network.
 *
 * Everything a terminal screen actually shows lives in [TerminalContent] instead, because the wireless
 * terminal shows exactly the same thing while being an item on nobody's network. What is left here is
 * the part that genuinely belongs to a block.
 */
abstract class AbstractTerminal(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(pos, state, data), StorageEndPoint {

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    /**
     * Drop-off slots. Shift-clicking from the player inventory lands here and is pushed into the
     * network on the next tick; anything the network cannot take stays visible instead of vanishing.
     */
    protected val depositInventory = storedInventory("deposit", DEPOSIT_SLOTS)

    private val content = TerminalContent { storageNetwork }

    protected val entries: MutableProvider<List<StorageEntry>>
        get() = content.entries

    init {
        holders += storageHolder
    }

    override fun handleTick() {
        drainDeposit()
        // the lamp in the menu says what the block's face says, so one signal redraws both
        if (setPowered(storageNetwork?.isOnline == true))
            networkItem.notifyWindows()

        // Rebuilding the index means taking the global lock and walking every cell and container on the
        // network. The provider it feeds has no subscribers until someone opens this terminal, so
        // without the guard every terminal ever placed does that work once a second, forever, for
        // nobody. Each menu refreshes on open so the first look is still current.
        val interval = max(1, TERMINAL_REFRESH_TICKS)
        if (serverTick % interval == 0 && menuContainer.getMenus<TileEntityMenu>().any())
            refreshEntries()
    }

    override fun handleDisable() {
        storageNetwork = null
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        storageNetwork = null
        super.handleBreak(ctx)
    }

    private fun drainDeposit() {
        // Most ticks there is nothing to push, so bail before even looking the network up: reading it
        // means two volatiles and, through isOnline, the tick delay config provider — cheap, but this
        // runs per terminal per tick. getUnsafeItem because the guard only needs to look at the slots,
        // not own a copy of them.
        if ((0..<depositInventory.size).none { depositInventory.getUnsafeItem(it)?.isEmpty == false })
            return

        val network = storageNetwork?.takeIf { it.isOnline } ?: return

        for (slot in 0..<depositInventory.size) {
            val stack = depositInventory.getItem(slot) ?: continue
            val type = ItemType.of(stack) ?: continue

            val leftover = network.insert(type, stack.amount.toLong()).toInt()
            if (leftover == stack.amount)
                continue

            val updated = if (leftover <= 0) null else stack.clone().apply { amount = leftover }
            // the slot can refuse the write; taking back what was just stored is the only way to avoid
            // the items existing in the network and in the slot at the same time
            if (!depositInventory.setItem(SELF_UPDATE_REASON, slot, updated))
                network.extract(type, (stack.amount - leftover).toLong())
        }
    }

    protected fun refreshEntries() = content.refresh()

    /**
     * The lamp that says whether a controller is keeping this terminal running — see [networkStatusIcon].
     *
     * One per *block* rather than one per menu, even though a terminal's menu is built per player: what
     * it draws does not depend on who is looking, and an InvUI item notifies every window it appears in.
     * So the tick can redraw it for everybody without going through the menus at all.
     */
    internal val networkItem = ClickableItem({ networkStatusIcon(storageNetwork) })

    //<editor-fold desc="gui building blocks, all of them the content's", defaultstate="collapsed">

    protected fun contentProvider(
        player: Player,
        filter: Provider<String>,
        sortMode: Provider<SortMode>,
        columns: Int,
        visibleSlots: Int
    ): Provider<List<Item>> = content.contentProvider(player, filter, sortMode, columns, visibleSlots)

    protected fun contentGui(
        items: Provider<List<Item>>,
        vararg structure: String,
        extraIngredients: IngredientsDsl.() -> Unit = {}
    ): ScrollGui<Item> = content.contentGui(items, *structure, extraIngredients = extraIngredients)

    protected fun sortButton(sortMode: MutableProvider<SortMode>): Item = content.sortButton(sortMode)

    protected fun clearFilterButton(filter: MutableProvider<String>): Item = content.clearFilterButton(filter)

    protected fun depositBackground(): ItemProvider = TerminalContent.depositBackground()

    protected fun searchState(): MutableProvider<String> = TerminalContent.searchState()

    protected fun sortState(): MutableProvider<SortMode> = TerminalContent.sortState()

    //</editor-fold>

}
