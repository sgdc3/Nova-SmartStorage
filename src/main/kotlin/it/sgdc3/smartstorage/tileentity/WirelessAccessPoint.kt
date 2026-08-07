package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.ClickableItem
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
     * What the block last told anyone it was doing, so the two readouts it has — its face and its menu —
     * are only redrawn when that changes.
     */
    private var renderedOnline: Boolean? = null

    /**
     * The block has nothing else to do per tick; this is here because its face is a status readout and
     * a readout that lies is worse than none. The same goes for the menu, which nothing else would ever
     * refresh: it has no contents to change, so without this it froze at whatever it said when opened.
     */
    override fun handleTick() {
        val online = storageNetwork?.isOnline == true
        setPowered(online)

        if (online != renderedOnline) {
            renderedOnline = online
            menuContainer.forEachMenu(WirelessAccessPointMenu::update)
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

    @TileEntityMenuClass
    inner class WirelessAccessPointMenu : GlobalTileEntityMenu(GuiTextures.WIRELESS_ACCESS_POINT) {

        private val statusItem = ClickableItem({ statusIcon() })

        override val gui = Gui.builder()
            .setStructure(
                ". . . . . . . . .",
                ". . . . i . . . .",
                ". . . . . . . . ."
            )
            .addIngredient('i', statusItem)
            .build()

        fun update() = statusItem.notifyWindows()

        private fun statusIcon(): ItemBuilder {
            val online = storageNetwork?.isOnline == true

            val builder = ItemBuilder(if (online) Material.ENDER_PEARL else Material.BARRIER)
            builder.setName(
                Component.translatable("menu.smartstorage.access_point.title").withoutPreFormatting()
            )
            builder.setLore(
                listOf(
                    if (online)
                        Component.translatable("menu.smartstorage.status.online", NamedTextColor.GREEN)
                            .withoutPreFormatting()
                    else
                        Component.translatable("menu.smartstorage.status.disconnected", NamedTextColor.RED)
                            .withoutPreFormatting(),
                    Component.translatable("menu.smartstorage.access_point.hint", NamedTextColor.DARK_GRAY)
                        .withoutPreFormatting()
                )
            )
            return builder
        }

    }

}
