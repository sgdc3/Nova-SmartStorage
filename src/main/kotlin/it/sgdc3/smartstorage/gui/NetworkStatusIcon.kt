package it.sgdc3.smartstorage.gui

import it.sgdc3.smartstorage.network.OfflineReason
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.registry.GuiItems
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting

/**
 * Whether the device whose menu this is in is being kept running, and if not, why not.
 *
 * Shared for the same reason [priorityIcon] is: a player who has learnt what the lamp means on a drive
 * bay should not have to learn it again on a terminal. Every device on a storage network shows this and
 * shows it identically — the blocks that are *not* on one, a barrel and its controller, deliberately do
 * not, because "online" is not a question they have an answer to.
 *
 * The reason matters as much as the state. "Offline" sends a player looking for the fault; "more than
 * one controller" tells them where it is, from whichever block they happened to open.
 */
internal fun networkStatusIcon(network: StorageNetwork?): ItemBuilder {
    // Not the same question. A device with no network at all was never wired to anything; a device with
    // one that is not online is wired to a system that has something wrong with it, and the system knows
    // what. Reading the reason from a network that is merely stale would report whatever it last was.
    val reason = when {
        network == null -> OfflineReason.DISCONNECTED
        !network.isOnline -> network.status.offlineReason ?: OfflineReason.DISCONNECTED
        else -> null
    }

    val state = Component.translatable(
        reason?.localizationKey ?: "menu.smartstorage.status.online",
        if (reason == null) NamedTextColor.GREEN else NamedTextColor.RED
    )

    return (if (reason == null) GuiItems.STATUS_ONLINE else GuiItems.STATUS_OFFLINE)
        .createClientsideItemBuilder()
        .setName(
            Component.translatable("menu.smartstorage.status.network", NamedTextColor.GRAY, state)
                .withoutPreFormatting()
        )
        .addLoreLines(
            Component.translatable("menu.smartstorage.status.hint", NamedTextColor.DARK_GRAY)
                .withoutPreFormatting()
        )
}
