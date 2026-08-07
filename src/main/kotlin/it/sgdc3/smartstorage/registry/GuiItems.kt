package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage.item
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * Items that only ever exist inside a menu.
 */
@Init(stage = InitStage.PRE_PACK)
object GuiItems {

    /**
     * Icon for the storage upgrade in Simple-Upgrades' upgrades menu.
     */
    val STORAGE_UPGRADE: NovaItem = guiItem("storage_upgrade")
    val VOID_UPGRADE: NovaItem = guiItem("void_upgrade")

    /**
     * The lamp on the storage controller's status, which used to be a dye — a stand-in that read as an
     * item somebody had left in the menu rather than as part of it.
     */
    val STATUS_ONLINE: NovaItem = guiItem("status_online")
    val STATUS_OFFLINE: NovaItem = guiItem("status_offline")

    /**
     * Placeholders drawn in empty filter slots. Same artwork, different names: an empty slot with no
     * label tells the player nothing about which of the two directions it governs.
     */
    val STORAGE_FILTER_PLACEHOLDER: NovaItem = filterPlaceholder("storage_filter", "menu.smartstorage.filter.storage")
    val INSERT_FILTER_PLACEHOLDER: NovaItem = filterPlaceholder("insert_filter", "menu.smartstorage.filter.insert")
    val EXTRACT_FILTER_PLACEHOLDER: NovaItem = filterPlaceholder("extract_filter", "menu.smartstorage.filter.extract")

    private fun guiItem(name: String, localizedName: String? = null): NovaItem = item("gui/$name") {
        if (localizedName == null) name(null) else localizedName(localizedName)
        hidden(true)
        modelDefinition { model = buildModel { createGuiModel(background = true, stretched = false, "item/gui/$name") } }
    }

    /**
     * Transparent so the slot underneath still shows through; all three share one texture.
     */
    private fun filterPlaceholder(name: String, localizedName: String): NovaItem = item("gui/placeholder/$name") {
        localizedName(localizedName)
        hidden(true)
        modelDefinition {
            model = buildModel { createGuiModel(background = false, stretched = false, "item/gui/placeholder/filter") }
        }
    }

}
