package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage
import xyz.xenondevs.nova.addon.simpleupgrades.UpgradeType
import xyz.xenondevs.nova.addon.simpleupgrades.registry.UpgradeTypeRegistry
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage

/**
 * Upgrade types this addon adds to Simple-Upgrades.
 *
 * Simple-Upgrades ships speed, efficiency, energy, fluid and range — none of which means "more disk
 * slots", so the drive bay gets its own. The values per level live under `upgrade_values.storage` in
 * each block's config.
 */
@Init(stage = InitStage.POST_PACK_PRE_WORLD)
object UpgradeTypes {

    val STORAGE: UpgradeType<Int> = UpgradeTypeRegistry.registerUpgradeType(
        SmartStorage,
        "storage",
        Items.STORAGE_UPGRADE,
        GuiItems.STORAGE_UPGRADE
    )

    /**
     * Makes a barrel take items it has no room for and burn the excess.
     *
     * A switch rather than a scale, so its value list is `[0, 1]` and installing a second one is
     * refused by Simple-Upgrades on its own — the list length is the limit.
     */
    val VOID: UpgradeType<Int> = UpgradeTypeRegistry.registerUpgradeType(
        SmartStorage,
        "void",
        Items.VOID_UPGRADE,
        GuiItems.VOID_UPGRADE
    )

}
