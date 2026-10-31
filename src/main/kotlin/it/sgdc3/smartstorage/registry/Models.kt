@file:Suppress("unused")

package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage.item
import org.bukkit.inventory.ItemType
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.registry.RegistryEntry

/**
 * Items that exist only to carry a model for a display entity. They are hidden, so they never reach a
 * creative tab or a recipe book.
 */
@Init(stage = InitStage.PRE_PACK)
object Models {

    /**
     * The port a [it.sgdc3.smartstorage.tileentity.StorageConnector] grows against a container. It is
     * not part of the block model because containers are not block state: a chest can be placed or
     * broken next to a connector without the connector's own state changing.
     */
    val CONNECTOR_ATTACHMENT: RegistryEntry.Paper<ItemType> = port("connector", lit = true)

    /**
     * The same, for a [it.sgdc3.smartstorage.tileentity.StorageInterface] against an item network
     * endpoint.
     */
    val INTERFACE_ATTACHMENT: RegistryEntry.Paper<ItemType> = port("interface", lit = true)

    /**
     * The dark twins, worn while the controller is not keeping the hub running. A display entity carries
     * one model and cannot pick a texture, so an unlit port has to be a second item.
     */
    val CONNECTOR_ATTACHMENT_OFF: RegistryEntry.Paper<ItemType> = port("connector", lit = false)
    val INTERFACE_ATTACHMENT_OFF: RegistryEntry.Paper<ItemType> = port("interface", lit = false)

    /**
     * And the same four for the fluid pair, which are separate blocks with their own artwork.
     */
    val FLUID_CONNECTOR_ATTACHMENT: RegistryEntry.Paper<ItemType> = port("fluid_connector", lit = true)
    val FLUID_INTERFACE_ATTACHMENT: RegistryEntry.Paper<ItemType> = port("fluid_interface", lit = true)
    val FLUID_CONNECTOR_ATTACHMENT_OFF: RegistryEntry.Paper<ItemType> = port("fluid_connector", lit = false)
    val FLUID_INTERFACE_ATTACHMENT_OFF: RegistryEntry.Paper<ItemType> = port("fluid_interface", lit = false)

    /**
     * The nozzle an [it.sgdc3.smartstorage.tileentity.EnergyValve] wears towards a machine.
     *
     * The same mechanism as the other hubs' ports and the same meaning: this side is serving something.
     * It stays on whether or not the valve is currently feeding it — what a nozzle says is that there is
     * a machine there, and whether power is going through is the core's to say.
     */
    val VALVE_PORT: RegistryEntry.Paper<ItemType> = port("energy_valve", lit = true)
    val VALVE_PORT_OFF: RegistryEntry.Paper<ItemType> = port("energy_valve", lit = false)

    /**
     * The valve's arms, one per pipe tier, each carrying that tier's own texture.
     *
     * Display entities rather than block state, because the valve decides its colour a face at a time —
     * six faces over six tiers is more combinations than any state could hold, and only one model of
     * each is needed once the entity does the turning.
     *
     * `plain` is this addon's own cable and the answer for everything that is not a tier we know: a
     * machine, one of our own blocks, a pipe from somewhere else. The rest name Logistics' textures, and
     * are only ever asked for after one of its cables has been found — so a server without Logistics
     * never reaches them and never shows a hole.
     */
    val VALVE_ARMS: Map<String, RegistryEntry.Paper<ItemType>> =
        listOf("plain", "basic", "advanced", "elite", "ultimate", "creative")
            .associateWith { tier ->
                item("energy_valve_arm_$tier") {
                    hidden(true)
                    modelDefinition { model = buildModel { getModel("block/energy_valve/arm/$tier") } }
                }
            }

    /**
     * The cores the four storage-network hubs wear, now that their block model is the cable's.
     *
     * A hub's arms are the cable's arms to the unit, and its core is an eight-cube that hides the
     * cable's four-cube inside it — so the block model can be `block/cable/<id>` and the core can ride on
     * top as a display entity. Sixty-four models serve five blocks where each used to carry a hundred and
     * twenty-eight of its own.
     */
    val CONNECTOR_CORE: RegistryEntry.Paper<ItemType> = core("connector", lit = true)
    val INTERFACE_CORE: RegistryEntry.Paper<ItemType> = core("interface", lit = true)
    val FLUID_CONNECTOR_CORE: RegistryEntry.Paper<ItemType> = core("fluid_connector", lit = true)
    val FLUID_INTERFACE_CORE: RegistryEntry.Paper<ItemType> = core("fluid_interface", lit = true)

    val CONNECTOR_CORE_OFF: RegistryEntry.Paper<ItemType> = core("connector", lit = false)
    val INTERFACE_CORE_OFF: RegistryEntry.Paper<ItemType> = core("interface", lit = false)
    val FLUID_CONNECTOR_CORE_OFF: RegistryEntry.Paper<ItemType> = core("fluid_connector", lit = false)
    val FLUID_INTERFACE_CORE_OFF: RegistryEntry.Paper<ItemType> = core("fluid_interface", lit = false)

    private fun core(hub: String, lit: Boolean): RegistryEntry.Paper<ItemType> {
        val path = if (lit) "core" else "core_off"
        return item(if (lit) "${hub}_core" else "${hub}_core_off") {
            hidden(true)
            modelDefinition { model = buildModel { getModel("block/$hub/$path") } }
        }
    }

    private fun port(hub: String, lit: Boolean): RegistryEntry.Paper<ItemType> {
        val path = if (lit) "attachment" else "attachment_off"
        return item(if (lit) "${hub}_attachment" else "${hub}_attachment_off") {
            hidden(true)
            modelDefinition { model = buildModel { getModel("block/$hub/$path") } }
        }
    }

}
