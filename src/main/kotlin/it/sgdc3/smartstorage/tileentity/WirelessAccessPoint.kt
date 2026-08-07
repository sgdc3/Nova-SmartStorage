package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
import it.sgdc3.smartstorage.gui.networkStatusIcon
import it.sgdc3.smartstorage.network.StorageEndPoint
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.network.WirelessNode
import it.sgdc3.smartstorage.registry.GuiTextures
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass

/**
 * The thing a wireless terminal actually talks to.
 *
 * It is an ordinary device on the storage network — it counts towards the controller's device limit and
 * draws the same energy per device as anything else — and does nothing at all on its own. Its whole job
 * is to exist somewhere, so that a terminal bound to this network has something to be near.
 *
 * It has no reach of its own, on purpose. Reach belongs to the terminal: that is the thing that gets
 * carried away and the thing that takes upgrades, so it is the thing that should answer "can I see the
 * network from here". Putting a number on the point as well would mean two places to look when the
 * answer is no.
 */
class WirelessAccessPoint(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : NetworkedTileEntity(pos, state, data), StorageEndPoint, WirelessNode {

    override val storageHolder = StorageHolder(this)

    @Volatile
    override var storageNetwork: StorageNetwork? = null

    init {
        holders += storageHolder
    }

    /**
     * The block has nothing else to do per tick; this is here because its face is a status readout and
     * a readout that lies is worse than none. The same goes for the menu, which nothing else would ever
     * refresh: it has no contents to change, so without this it froze at whatever it said when opened.
     */
    override fun handleTick() {
        if (setPowered(storageNetwork?.isOnline == true))
            menuContainer.forEachMenu(WirelessAccessPointMenu::update)
    }

    override fun handleDisable() {
        storageNetwork = null
        super.handleDisable()
    }

    override fun handleBreak(ctx: Context<BlockBreak>) {
        storageNetwork = null
        super.handleBreak(ctx)
    }

    @TileEntityMenuClass
    inner class WirelessAccessPointMenu : GlobalTileEntityMenu(GuiTextures.WIRELESS_ACCESS_POINT) {

        private val statusItem = ClickableItem({ statusIcon() })

        /**
         * The same lamp every other device shows, rather than this block's own bespoke one. An access
         * point that is not on a live network is doing nothing at all, so it is the block where that
         * question matters most — which is no reason for it to be phrased differently here.
         */
        private val networkItem = ClickableItem({ networkStatusIcon(storageNetwork) })

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . .",
                ". . . n i . . . .",
                ". . . . . . . . ."
            )
            .addIngredient('i', statusItem)
            .addIngredient('n', networkItem)
            .build()

        fun update() {
            statusItem.notifyWindows()
            networkItem.notifyWindows()
        }

        private fun statusIcon(): ItemBuilder = ItemBuilder(Material.ENDER_PEARL)
            .setName(Component.translatable("menu.smartstorage.access_point.title").withoutPreFormatting())
            .setLore(
                listOf(
                    Component.translatable("menu.smartstorage.access_point.hint", NamedTextColor.DARK_GRAY)
                        .withoutPreFormatting()
                )
            )

    }

}
