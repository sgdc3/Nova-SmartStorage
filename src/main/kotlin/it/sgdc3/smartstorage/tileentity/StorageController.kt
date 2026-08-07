package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.item.WirelessTerminalBehavior
import it.sgdc3.smartstorage.network.OfflineReason
import it.sgdc3.smartstorage.network.StorageControllerNode
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.network.StorageNetworkStatus
import it.sgdc3.smartstorage.network.StorageTotals
import it.sgdc3.smartstorage.registry.Blocks.STORAGE_CONTROLLER
import it.sgdc3.smartstorage.registry.GuiTextures
import it.sgdc3.smartstorage.registry.NetworkTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import it.sgdc3.smartstorage.registry.GuiItems
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.commons.provider.Provider
import xyz.xenondevs.commons.provider.combinedProvider
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.addon.simpleupgrades.gui.OpenUpgradesItem
import xyz.xenondevs.nova.addon.simpleupgrades.registry.UpgradeTypes
import xyz.xenondevs.nova.addon.simpleupgrades.storedEnergyHolder
import xyz.xenondevs.nova.addon.simpleupgrades.storedUpgradeHolder
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.context.intention.BlockInteract
import xyz.xenondevs.nova.ui.menu.EnergyBar
import xyz.xenondevs.nova.ui.menu.sideconfig.OpenSideConfigItem
import xyz.xenondevs.nova.ui.menu.sideconfig.SideConfigMenu
import xyz.xenondevs.nova.util.NumberFormatUtils
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.InteractionResult
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import kotlin.math.roundToLong

private val MAX_ENERGY = STORAGE_CONTROLLER.config.entry<Long>("max_energy")
private val ENERGY_PER_DEVICE = STORAGE_CONTROLLER.config.entry<Long>("energy_per_device")
private val ENERGY_PER_CELL = STORAGE_CONTROLLER.config.entry<Long>("energy_per_cell")
private val MAX_DEVICES_PROVIDER = STORAGE_CONTROLLER.config.entry<Int>("max_devices")

/**
 * Powers and bounds a storage network. Exactly one is required: with none the network is inert, with
 * two the network refuses to run rather than picking a winner.
 *
 * The controller is an end point of both the storage network and Nova's energy network, so it can be
 * fed by any Nova generator or power cell.
 */
class StorageController(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(pos, state, data), StorageControllerNode {

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    private val upgradeHolder = storedUpgradeHolder(UpgradeTypes.ENERGY, UpgradeTypes.EFFICIENCY)
    private val energyHolder = storedEnergyHolder(MAX_ENERGY, upgradeHolder, NetworkConnectionType.INSERT)

    // config values are per server tick; the storage network only ticks every tick_delay ticks, so the
    // draw is scaled accordingly. Efficiency upgrades divide the cost, as they do for Nova machines.
    private val energyPerDevice by energyDraw(ENERGY_PER_DEVICE)
    private val energyPerCell by energyDraw(ENERGY_PER_CELL)

    @Volatile
    override var status: StorageNetworkStatus = StorageNetworkStatus.offline(OfflineReason.NO_CONTROLLER)
        private set

    private var renderedStatus: StorageNetworkStatus? = null
    private var renderedTotals: StorageTotals? = null

    override val maxDevices: Int by MAX_DEVICES_PROVIDER

    init {
        holders += storageHolder
    }

    private fun energyDraw(base: Provider<Long>) =
        combinedProvider(
            base,
            NetworkTypes.TICK_DELAY_PROVIDER,
            upgradeHolder.getValueProvider(UpgradeTypes.EFFICIENCY)
        ) { perTick, tickDelay, efficiency -> (perTick * tickDelay / efficiency).roundToLong() }

    override fun tryConsume(devices: Int, cells: Int): Boolean {
        val cost = devices * energyPerDevice + cells * energyPerCell
        if (cost <= 0L)
            return true

        if (energyHolder.energy < cost)
            return false

        energyHolder.energy -= cost
        return true
    }

    /**
     * Called from the network tick, which runs off the main thread — so this only stores the value and
     * lets [handleTick] push it to open menus.
     */
    override fun updateStatus(status: StorageNetworkStatus) {
        this.status = status
    }

    /**
     * The status as a menu should show it.
     *
     * [status] is pushed in by the network tick and has no expiry of its own, so a controller orphaned
     * by a topology change goes on reporting whatever it last read — green, with counts from devices
     * that may since have been broken and dropped on the floor. Every other surface reads the network
     * through [StorageNetwork.isOnline], which carries the staleness check; without this the controller
     * would be the one place that contradicts them, which is precisely where a player looks to find out
     * what went wrong.
     */
    private val displayStatus: StorageNetworkStatus
        get() {
            val status = this.status
            return if (status.isOnline && storageNetwork?.isOnline != true)
                StorageNetworkStatus.offline(OfflineReason.DISCONNECTED)
            else status
        }

    /**
     * Redraws the status icon when anything it shows has changed.
     *
     * The totals have to be part of that test, not just the status. Moving items changes neither the
     * device count nor the cell count nor the offline reason, so a check against [StorageNetworkStatus]
     * alone compares equal on every tick and the "items" and "types" lines freeze at whatever they read
     * when something else last dirtied the slot — an InvUI item is only re-rendered when it is notified.
     *
     * Nothing outside an open menu reads any of this, so the walk is gated on there being one.
     */
    override fun handleTick() {
        // before the menu guard: whether the controller's own face is lit is not something only a
        // player with the menu open should be told
        setPowered(displayStatus.isOnline)

        if (menuContainer.getMenus<StorageControllerMenu>().none())
            return

        val status = displayStatus
        val totals = if (status.isOnline) storageNetwork?.totals() ?: StorageTotals.EMPTY else StorageTotals.EMPTY

        if (status != renderedStatus || totals != renderedTotals) {
            renderedStatus = status
            renderedTotals = totals
            menuContainer.forEachMenu(StorageControllerMenu::update)
        }
    }

    override fun handleDisable() {
        storageNetwork = null
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        storageNetwork = null
        super.handleBreak(ctx)
    }

    /**
     * Right-clicking the controller with a wireless terminal binds it to this network.
     *
     * The controller does this rather than the item, because the controller is what a binding *is*: it
     * is the block that defines which system a terminal is looking at, and putting the check here means
     * the item never has to work out what it was clicked on. Anything else falls through to [use], which
     * opens the menu.
     */
    override fun useItemOn(ctx: Context<BlockInteract>): InteractionResult {
        val player = ctx[BlockInteract.SOURCE_PLAYER] ?: return InteractionResult.Pass
        val hand = ctx[BlockInteract.HELD_HAND] ?: return InteractionResult.Pass

        // an equipment slot hands back an empty stack rather than null, which is simply not a terminal
        val held = player.inventory.getItem(hand)
        val terminal = WirelessTerminalBehavior.of(held) ?: return InteractionResult.Pass

        terminal.bind(held, this)
        player.inventory.setItem(hand, held)

        player.playClickSound()
        player.sendActionBar(
            Component.translatable("menu.smartstorage.wireless.bound_now", NamedTextColor.GREEN)
        )
        return InteractionResult.Success(swing = true)
    }

    @TileEntityMenuClass
    inner class StorageControllerMenu : GlobalTileEntityMenu(GuiTextures.STORAGE_CONTROLLER) {

        private val sideConfigMenu = SideConfigMenu(this@StorageController, ::openWindow)
        private val statusItem = ClickableItem({ statusIcon() })

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . .",
                ". s u . . . . e .",
                ". . . . i . . e .",
                ". . . . . . . e .",
                ". . . . . . . . ."
            )
            .addIngredient('s', OpenSideConfigItem(sideConfigMenu))
            .addIngredient('u', OpenUpgradesItem(upgradeHolder))
            .addIngredient('i', statusItem)
            .addIngredient('e', EnergyBar(3, energyHolder))
            .build()

        fun update() {
            statusItem.notifyWindows()
        }

        private fun statusIcon(): ItemBuilder {
            val status = this@StorageController.displayStatus
            // whatever handleTick last measured, rather than a fresh walk: this runs once per viewer per
            // re-render, and the totals are the same for all of them
            val totals = renderedTotals ?: StorageTotals.EMPTY
            val builder = (if (status.isOnline) GuiItems.STATUS_ONLINE else GuiItems.STATUS_OFFLINE)
                .createClientsideItemBuilder()

            builder.setName(
                Component.translatable(
                    if (status.isOnline) "menu.smartstorage.status.online" else status.offlineReason!!.localizationKey,
                    if (status.isOnline) NamedTextColor.GREEN else NamedTextColor.RED
                ).withoutPreFormatting()
            )

            val lore = ArrayList<Component>()
            lore += Component.translatable(
                "menu.smartstorage.controller.devices",
                NamedTextColor.GRAY,
                Component.text(status.devices, NamedTextColor.GREEN),
                // any negative disables the cap in StorageNetworkGroup.computeStatus, so all of
                // them have to read the same here rather than printing a raw "-1"
                if (maxDevices < 0)
                    Component.text("∞", NamedTextColor.GREEN)
                else
                    Component.text(maxDevices, NamedTextColor.GREEN)
            ).withoutPreFormatting()
            lore += Component.translatable(
                "menu.smartstorage.controller.cells",
                NamedTextColor.GRAY,
                Component.text(status.cells, NamedTextColor.GREEN)
            ).withoutPreFormatting()
            lore += Component.translatable(
                "menu.smartstorage.controller.items",
                NamedTextColor.GRAY,
                Component.text(totals.usedCount, NamedTextColor.GREEN),
                Component.text(totals.totalCount, NamedTextColor.GREEN)
            ).withoutPreFormatting()
            lore += Component.translatable(
                "menu.smartstorage.controller.types",
                NamedTextColor.GRAY,
                Component.text(totals.usedTypes, NamedTextColor.GREEN),
                Component.text(totals.totalTypes, NamedTextColor.GREEN)
            ).withoutPreFormatting()

            // only on a network that has some: a system of storage cells being told it holds 0 of 0
            // buckets is a line about something that is not there
            if (totals.hasFluidStorage) {
                lore += Component.translatable(
                    "menu.smartstorage.controller.fluid",
                    NamedTextColor.GRAY,
                    Component.text(
                        NumberFormatUtils.getFluidString(totals.usedFluid, totals.totalFluid),
                        NamedTextColor.GREEN
                    )
                ).withoutPreFormatting()
            }

            builder.setLore(lore)
            return builder
        }

    }

}
