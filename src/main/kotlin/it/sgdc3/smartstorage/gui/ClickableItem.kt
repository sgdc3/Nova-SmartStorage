package it.sgdc3.smartstorage.gui

import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import xyz.xenondevs.invui.Click
import xyz.xenondevs.invui.item.AbstractItem
import xyz.xenondevs.invui.item.ItemProvider

/**
 * A GUI item whose icon and click behaviour are supplied as lambdas, so simple buttons don't each need
 * their own class.
 */
internal class ClickableItem(
    private val provider: (Player) -> ItemProvider,
    private val onClick: (ClickType, Player, Click) -> Unit = { _, _, _ -> }
) : AbstractItem() {

    override fun getItemProvider(player: Player): ItemProvider = provider(player)

    override fun handleClick(clickType: ClickType, player: Player, click: Click) =
        onClick(clickType, player, click)

}
