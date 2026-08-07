@file:Suppress("unused")

package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage.item
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem

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
    val CONNECTOR_ATTACHMENT: NovaItem = port("connector", lit = true)

    /**
     * The same, for a [it.sgdc3.smartstorage.tileentity.StorageInterface] against an item network
     * endpoint.
     */
    val INTERFACE_ATTACHMENT: NovaItem = port("interface", lit = true)

    /**
     * The dark twins, worn while the controller is not keeping the hub running. A display entity carries
     * one model and cannot pick a texture, so an unlit port has to be a second item.
     */
    val CONNECTOR_ATTACHMENT_OFF: NovaItem = port("connector", lit = false)
    val INTERFACE_ATTACHMENT_OFF: NovaItem = port("interface", lit = false)

    /**
     * And the same four for the fluid pair, which are separate blocks with their own artwork.
     */
    val FLUID_CONNECTOR_ATTACHMENT: NovaItem = port("fluid_connector", lit = true)
    val FLUID_INTERFACE_ATTACHMENT: NovaItem = port("fluid_interface", lit = true)
    val FLUID_CONNECTOR_ATTACHMENT_OFF: NovaItem = port("fluid_connector", lit = false)
    val FLUID_INTERFACE_ATTACHMENT_OFF: NovaItem = port("fluid_interface", lit = false)

    private fun port(hub: String, lit: Boolean): NovaItem {
        val path = if (lit) "attachment" else "attachment_off"
        return item(if (lit) "${hub}_attachment" else "${hub}_attachment_off") {
            hidden(true)
            modelDefinition { model = buildModel { getModel("block/$hub/$path") } }
        }
    }

}
