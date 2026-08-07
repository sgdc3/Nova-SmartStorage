package it.sgdc3.smartstorage.gui

import it.sgdc3.smartstorage.network.DEFAULT_PRIORITY
import it.sgdc3.smartstorage.network.PRIORITY_RANGE
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.world.item.DefaultGuiItems

/**
 * The number between the two arrows, wherever a priority can be set.
 *
 * Shared because a priority means the same thing on a drive bay as it does on one side of a connector,
 * and a player who reads what it does on one should not have to wonder whether the other works the same
 * way. Nova's numbered GUI item carries the digits; the lore carries the rule.
 */
internal fun priorityIcon(priority: Int): ItemBuilder =
    DefaultGuiItems.NUMBER.createClientsideItemBuilder()
        .addCustomModelData(priority)
        .setName(
            Component.translatable(
                "menu.smartstorage.priority",
                NamedTextColor.GRAY,
                Component.text(priority, NamedTextColor.GREEN)
            ).withoutPreFormatting()
        )
        .addLoreLines(
            Component.translatable("menu.smartstorage.priority.order", NamedTextColor.DARK_GRAY)
                .withoutPreFormatting(),
            Component.translatable(
                "menu.smartstorage.priority.range",
                NamedTextColor.DARK_GRAY,
                Component.text(PRIORITY_RANGE.first),
                Component.text(PRIORITY_RANGE.last),
                Component.text(DEFAULT_PRIORITY)
            ).withoutPreFormatting()
        )
