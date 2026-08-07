package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.networkStatusIcon
import it.sgdc3.smartstorage.network.StorageEndPoint
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.network.StorageTotals
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.TERMINAL_REFRESH_TICKS
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.util.NumberFormatUtils
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType
import kotlin.math.max

/**
 * What a bucket is worth in Nova's fluid units. Its own name because it is the unit of every
 * interaction here — a fluid terminal deals in buckets, not in millibuckets.
 */
private const val BUCKET = 1000L

/**
 * The window into a storage network's fluids: see what is in there, and move it a bucket at a time.
 *
 * A separate block from the item terminal rather than a tab in it, because the two have almost nothing
 * in common past the name. The item terminal is a scrolling, searchable, sortable list of hundreds of
 * things; this is two rows. Search and sort would be furniture, and the click language is different —
 * you cannot put a bucket "on the cursor" half full.
 */
class FluidTerminal(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(pos, state, data), StorageEndPoint {

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    /**
     * Drop-off slot for filled buckets, emptied into the network on the next tick with the empty bucket
     * left behind. Shift-clicking a stack of lava buckets in from the inventory is the only bulk
     * operation this block has, and the only one it needs.
     */
    private val depositInventory = storedInventory("deposit", 1)

    init {
        holders += storageHolder
    }

    override fun handleTick() {
        drainDeposit()
        // the lamp in the menu says what the block's face says, so one signal redraws both
        if (setPowered(storageNetwork?.isOnline == true))
            menuContainer.forEachMenu(FluidTerminalMenu::update)

        // reading an amount walks every cell and tank on the network, so it happens for an open menu or
        // not at all — the same guard the item terminals have, for the same reason
        val interval = max(1, TERMINAL_REFRESH_TICKS)
        if (serverTick % interval == 0 && menuContainer.getMenus<TileEntityMenu>().any())
            menuContainer.forEachMenu(FluidTerminalMenu::update)
    }

    override fun handleDisable() {
        storageNetwork = null
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        storageNetwork = null
        super.handleBreak(ctx)
    }

    private val network: StorageNetwork?
        get() = storageNetwork?.takeIf { it.isOnline }

    /**
     * The fluid a full bucket carries, or null if [stack] is not one.
     */
    private fun fluidOf(stack: ItemStack?): FluidType? = when (stack?.type) {
        Material.WATER_BUCKET -> FluidType.WATER
        Material.LAVA_BUCKET -> FluidType.LAVA
        else -> null
    }

    private fun drainDeposit() {
        val stack = depositInventory.getUnsafeItem(0)?.takeUnless(ItemStack::isEmpty) ?: return
        val fluid = fluidOf(stack) ?: return
        val network = network ?: return

        // one bucket per tick: the empty one has to go back into the same slot, so there is nowhere to
        // put the second one until the player takes the first
        val committed = pour(network, fluid)
        if (committed <= 0L)
            return

        val left = stack.amount - 1
        val emptied = ItemStack.of(Material.BUCKET)
        val updated = if (left <= 0) emptied else stack.clone().apply { amount = left }

        if (!depositInventory.setItem(SELF_UPDATE_REASON, 0, updated)) {
            // The slot refused the write; take the fluid straight back out rather than leave it both in
            // the network and in the bucket. Exactly what stayed, which is a whole bucket only in the
            // ordinary case — see pour.
            network.extractFluid(fluid, committed)
            return
        }

        // the stack was more than one bucket, so the empty one has nowhere to go but the world
        if (left > 0)
            pos.world.dropItemNaturally(pos.location.add(0.5, 1.0, 0.5), emptied)
    }

    /**
     * Empties one bucket of [fluid] into the network. Answers whether the whole bucket went in.
     *
     * All or nothing: half a bucket is not a thing a player can hold, so anything short is put straight
     * back rather than left as a fraction nobody can recover.
     *
     * The rollback is the interesting part, because it can fail. What went in may have landed somewhere
     * the network cannot pull it out of again — a tank on a connector port set to insert only is exactly
     * that — and then the fluid is in the network *and* still in the bucket. A bucket is atomic, so the
     * only two honest outcomes are "all of it moved" and "none of it moved"; anything in between is
     * settled by emptying the bucket, which loses fluid rather than creating it.
     */
    private fun pour(network: StorageNetwork, fluid: FluidType): Long {
        val left = network.insertFluid(fluid, BUCKET)
        if (left <= 0L)
            return BUCKET

        val poured = BUCKET - left
        if (poured <= 0L)
            return 0L

        val recovered = network.extractFluid(fluid, poured)
        if (recovered >= poured)
            return 0L

        // Stuck: the network kept some of it and will not give it back. Emptying the bucket destroys the
        // difference; leaving it full would hand the player a full bucket the network has already been
        // paid with. The figure is returned rather than rounded up to a bucket so that a caller which has
        // to undo this takes back exactly what stayed.
        return poured - recovered
    }

    /**
     * Takes one bucket of [fluid] out of the network. Answers whether it got a whole one.
     */
    private fun draw(network: StorageNetwork, fluid: FluidType): Boolean {
        val taken = network.extractFluid(fluid, BUCKET)
        if (taken >= BUCKET)
            return true

        // Put back the splash we could not fill a bucket with. Usually free — the extraction just made
        // that much room — but not guaranteed: it may have come from a tank on a port set to extract
        // only, which will not take it back. What cannot go back is lost, which is the safe direction:
        // the bucket stays empty either way.
        if (taken > 0L)
            network.insertFluid(fluid, taken)
        return false
    }

    @TileEntityMenuClass
    inner class FluidTerminalMenu : GlobalTileEntityMenu(GuiTextures.FLUID_TERMINAL) {

        private val statusItem = ClickableItem({ statusIcon() })
        private val networkItem = ClickableItem({ networkStatusIcon(storageNetwork) })
        private val fluidItems = FluidType.entries.map { fluid ->
            ClickableItem({ fluidIcon(fluid) }, { _, player, _ -> handleFluidClick(player, fluid) })
        }

        override val gui = Gui.builder()
            .setStructure(*structure())
            .apply {
                addIngredient('i', statusItem)
                addIngredient('n', networkItem)
                addIngredient('b', depositInventory)
                fluidItems.forEachIndexed { index, item -> addIngredient('1' + index, item) }
            }
            .build()

        fun update() {
            statusItem.notifyWindows()
            networkItem.notifyWindows()
            fluidItems.forEach(ClickableItem::notifyWindows)
        }

        /**
         * One slot per fluid Nova has, built rather than written out so that a third one would appear
         * instead of being silently dropped.
         */
        private fun structure(): Array<String> {
            val row = CharArray(9) { '.' }
            row[1] = 'n'
            row[2] = 'i'
            FluidType.entries.forEachIndexed { index, _ -> row[3 + index] = '1' + index }

            return arrayOf(
                ". . . . . . . . .",
                row.joinToString(" "),
                ". . . b . . . . ."
            )
        }

        /**
         * A bucket on the cursor is the whole interaction: a full one of this fluid goes in, an empty
         * one comes back out filled.
         */
        private fun handleFluidClick(player: Player, fluid: FluidType) {
            val network = network ?: return
            val cursor = player.itemOnCursor

            // a stack of buckets would need somewhere to put the ones that changed state, and the cursor
            // holds exactly one thing
            if (cursor.amount != 1)
                return

            when {
                cursor.type == Material.BUCKET -> {
                    if (!draw(network, fluid))
                        return
                    player.setItemOnCursor(fluid.bucket)
                }

                fluidOf(cursor) == fluid -> {
                    if (pour(network, fluid) <= 0L)
                        return
                    player.setItemOnCursor(ItemStack.of(Material.BUCKET))
                }

                else -> return
            }

            player.playClickSound()
            update()
        }

        private fun fluidIcon(fluid: FluidType): ItemBuilder {
            val network = network
            val amount = network?.amountOf(fluid) ?: 0L

            val builder = if (amount > 0L) ItemBuilder(fluid.bucket) else ItemBuilder(Material.BUCKET)
            builder.setName(Component.translatable(fluid.localizedName, NamedTextColor.GRAY).withoutPreFormatting())
            builder.setLore(
                listOf(
                    Component.text(NumberFormatUtils.getFluidString(amount), NamedTextColor.GREEN)
                        .withoutPreFormatting(),
                    Component.translatable("menu.smartstorage.fluid_terminal.hint", NamedTextColor.DARK_GRAY)
                        .withoutPreFormatting()
                )
            )
            return builder
        }

        /**
         * What the system holds. Deliberately no longer doubles as a connectivity readout — the lamp
         * beside it owns that question now, and two icons answering it could disagree.
         */
        private fun statusIcon(): ItemBuilder {
            val totals = storageNetwork?.totals() ?: StorageTotals.EMPTY

            val builder = ItemBuilder(Material.PAPER)
            builder.setName(Component.translatable("menu.smartstorage.fluid_terminal.title").withoutPreFormatting())
            builder.setLore(
                listOf(
                    if (totals.hasFluidStorage)
                        Component.translatable(
                            "menu.smartstorage.fluid_terminal.stored",
                            NamedTextColor.GRAY,
                            Component.text(
                                NumberFormatUtils.getFluidString(totals.usedFluid, totals.totalFluid),
                                NamedTextColor.GREEN
                            )
                        ).withoutPreFormatting()
                    else
                        Component.translatable("menu.smartstorage.fluid_terminal.no_storage", NamedTextColor.RED)
                            .withoutPreFormatting()
                )
            )
            return builder
        }

    }

}
