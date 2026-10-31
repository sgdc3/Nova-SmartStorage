package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage.item
import org.bukkit.inventory.ItemType
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.registry.RegistryEntry

/**
 * Items that only ever exist inside a menu.
 */
@Init(stage = InitStage.PRE_PACK)
object GuiItems {

    /**
     * Icon for the storage upgrade in Simple-Upgrades' upgrades menu.
     */
    val STORAGE_UPGRADE: RegistryEntry.Paper<ItemType> = guiItem("storage_upgrade")
    val VOID_UPGRADE: RegistryEntry.Paper<ItemType> = guiItem("void_upgrade")
    val COMPACTING_UPGRADE: RegistryEntry.Paper<ItemType> = guiItem("compacting_upgrade")

    /**
     * The lamp on the storage controller's status, which used to be a dye — a stand-in that read as an
     * item somebody had left in the menu rather than as part of it.
     */
    val STATUS_ONLINE: RegistryEntry.Paper<ItemType> = guiItem("status_online")
    val STATUS_OFFLINE: RegistryEntry.Paper<ItemType> = guiItem("status_offline")

    /**
     * The barrel's lock switch, which is a padlock rather than a coloured button because the block it
     * governs wears one: locking a barrel stamps a padlock across its front, and the control that does
     * it should be the same picture.
     */
    val LOCK_ON: RegistryEntry.Paper<ItemType> = guiItem("lock_on")
    val LOCK_OFF: RegistryEntry.Paper<ItemType> = guiItem("lock_off")

    /**
     * Placeholders drawn in empty filter slots. Same artwork, different names: an empty slot with no
     * label tells the player nothing about which of the two directions it governs.
     */
    val STORAGE_FILTER_PLACEHOLDER: RegistryEntry.Paper<ItemType> = filterPlaceholder("storage_filter", "menu.smartstorage.filter.storage")
    val INSERT_FILTER_PLACEHOLDER: RegistryEntry.Paper<ItemType> = filterPlaceholder("insert_filter", "menu.smartstorage.filter.insert")
    val EXTRACT_FILTER_PLACEHOLDER: RegistryEntry.Paper<ItemType> = filterPlaceholder("extract_filter", "menu.smartstorage.filter.extract")

    private fun guiItem(name: String, localizedName: String? = null): RegistryEntry.Paper<ItemType> = item("gui/$name") {
        if (localizedName == null) name(null) else localizedName(localizedName)
        hidden(true)
        modelDefinition { model = buildModel { createGuiModel(background = true, stretched = false, "item/gui/$name") } }
    }

    /**
     * Transparent so the slot underneath still shows through; all three share one texture.
     */
    private fun filterPlaceholder(name: String, localizedName: String): RegistryEntry.Paper<ItemType> = item("gui/placeholder/$name") {
        localizedName(localizedName)
        hidden(true)
        modelDefinition {
            model = buildModel { createGuiModel(background = false, stretched = false, "item/gui/placeholder/filter") }
        }
    }

}
