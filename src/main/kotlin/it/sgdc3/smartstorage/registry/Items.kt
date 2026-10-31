@file:Suppress("unused")

package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage.item
import it.sgdc3.smartstorage.SmartStorage.registerItem
import it.sgdc3.smartstorage.item.FluidCellBehavior
import it.sgdc3.smartstorage.item.StorageCellBehavior
import it.sgdc3.smartstorage.item.WirelessTerminalBehavior
import org.bukkit.inventory.ItemType
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.registry.RegistryEntry

@Init(stage = InitStage.PRE_PACK)
object Items {

    val STORAGE_CABLE: RegistryEntry.Paper<ItemType> = item(Blocks.STORAGE_CABLE) {
        // the block model of an unconnected cable is just the tiny core cube, so the item gets its own
        modelDefinition { model = buildModel { getModel("item/cable") } }
    }

    val STORAGE_CONTROLLER: RegistryEntry.Paper<ItemType> = registerItem(Blocks.STORAGE_CONTROLLER)
    val DRIVE_BAY: RegistryEntry.Paper<ItemType> = registerItem(Blocks.DRIVE_BAY)
    val STORAGE_TERMINAL: RegistryEntry.Paper<ItemType> = registerItem(Blocks.STORAGE_TERMINAL)
    val CRAFTING_TERMINAL: RegistryEntry.Paper<ItemType> = registerItem(Blocks.CRAFTING_TERMINAL)
    val FLUID_TERMINAL: RegistryEntry.Paper<ItemType> = registerItem(Blocks.FLUID_TERMINAL)
    val WIRELESS_ACCESS_POINT: RegistryEntry.Paper<ItemType> = registerItem(Blocks.WIRELESS_ACCESS_POINT)

    /**
     * The terminal you carry. Stacks to 1: each one remembers its own network and its own upgrades.
     */
    val WIRELESS_TERMINAL: RegistryEntry.Paper<ItemType> = item("wireless_terminal") {
        behaviors(WirelessTerminalBehavior)
        localizedName("item.smartstorage.wireless_terminal")
        maxStackSize(1)
    }
    val STORAGE_BARREL: RegistryEntry.Paper<ItemType> = registerItem(Blocks.STORAGE_BARREL)
    val BARREL_CONTROLLER: RegistryEntry.Paper<ItemType> = registerItem(Blocks.BARREL_CONTROLLER)
    // like the cable, an unconnected hub is a bare core floating in the middle of nothing, so the items
    // show a core with one port on it instead
    val STORAGE_INTERFACE: RegistryEntry.Paper<ItemType> = item(Blocks.STORAGE_INTERFACE) {
        modelDefinition { model = buildModel { getModel("item/interface") } }
    }

    val STORAGE_CONNECTOR: RegistryEntry.Paper<ItemType> = item(Blocks.STORAGE_CONNECTOR) {
        modelDefinition { model = buildModel { getModel("item/connector") } }
    }

    val FLUID_INTERFACE: RegistryEntry.Paper<ItemType> = item(Blocks.FLUID_INTERFACE) {
        modelDefinition { model = buildModel { getModel("item/fluid_interface") } }
    }

    val FLUID_CONNECTOR: RegistryEntry.Paper<ItemType> = item(Blocks.FLUID_CONNECTOR) {
        modelDefinition { model = buildModel { getModel("item/fluid_connector") } }
    }

    val ENERGY_VALVE: RegistryEntry.Paper<ItemType> = item(Blocks.ENERGY_VALVE) {
        modelDefinition { model = buildModel { getModel("item/energy_valve") } }
    }

    /**
     * Adds disk slots to a drive bay. Registered as a Simple-Upgrades upgrade type in [UpgradeTypes].
     */
    val STORAGE_UPGRADE: RegistryEntry.Paper<ItemType> = registerItem("storage_upgrade")

    /**
     * Makes a storage barrel swallow what it has no room for. Registered as an upgrade type in
     * [UpgradeTypes].
     */
    val VOID_UPGRADE: RegistryEntry.Paper<ItemType> = registerItem("void_upgrade")

    /**
     * Makes a storage barrel keep what it holds in the densest form the server has a recipe for.
     * Registered as an upgrade type in [UpgradeTypes].
     */
    val COMPACTING_UPGRADE: RegistryEntry.Paper<ItemType> = registerItem("compacting_upgrade")

    val STORAGE_CELL_1K: RegistryEntry.Paper<ItemType> = cell("1k")
    val STORAGE_CELL_4K: RegistryEntry.Paper<ItemType> = cell("4k")
    val STORAGE_CELL_16K: RegistryEntry.Paper<ItemType> = cell("16k")
    val STORAGE_CELL_64K: RegistryEntry.Paper<ItemType> = cell("64k")

    /**
     * Fluid cells go in the same drive bay as the ones above. Named by how many buckets they hold,
     * because that is the unit a player thinks in — millibuckets are what the network counts in.
     */
    val FLUID_CELL_16B: RegistryEntry.Paper<ItemType> = fluidCell("16b")
    val FLUID_CELL_64B: RegistryEntry.Paper<ItemType> = fluidCell("64b")
    val FLUID_CELL_256B: RegistryEntry.Paper<ItemType> = fluidCell("256b")
    val FLUID_CELL_1024B: RegistryEntry.Paper<ItemType> = fluidCell("1024b")

    /**
     * Cells stack to 1: each one carries its own contents, so they must stay distinguishable.
     */
    private fun cell(tier: String): RegistryEntry.Paper<ItemType> = item("storage_cell_$tier") {
        behaviors(StorageCellBehavior)
        localizedName("item.smartstorage.storage_cell_$tier")
        maxStackSize(1)
    }

    private fun fluidCell(tier: String): RegistryEntry.Paper<ItemType> = item("fluid_cell_$tier") {
        behaviors(FluidCellBehavior)
        localizedName("item.smartstorage.fluid_cell_$tier")
        maxStackSize(1)
    }

}
