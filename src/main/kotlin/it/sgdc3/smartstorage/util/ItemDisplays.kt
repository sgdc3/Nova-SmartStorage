package it.sgdc3.smartstorage.util

import org.bukkit.Location
import org.bukkit.inventory.ItemType
import org.joml.Quaternionf
import xyz.xenondevs.nova.packetentity.ItemDisplayMetadata
import xyz.xenondevs.nova.packetentity.PacketEntity
import xyz.xenondevs.nova.packetentity.packetItemDisplay

/**
 * One item display entity per key, all at the same [location]: the ports and the core a hub wears, or
 * the arms of a valve.
 *
 * [show] is given the whole picture every time and changes only what differs from the last one — a key
 * that keeps its place has its item and rotation rewritten in place, so redrawing a hub because one side
 * went dark does not make the other five blink.
 *
 * Main thread only, like the entities themselves.
 */
internal class ItemDisplays<K : Any>(private val location: () -> Location) {

    class Model(val item: ItemType, val rotation: Quaternionf = Quaternionf())

    private val displays = HashMap<K, PacketEntity<ItemDisplayMetadata>>()

    fun show(models: Map<K, Model>) {
        val gone = displays.keys - models.keys
        for (key in gone)
            displays.remove(key)?.despawn()

        for ((key, model) in models) {
            val display = displays[key]
            if (display != null) {
                display.metadata.itemStack = model.item.createItemStack()
                display.metadata.leftRotation = model.rotation
                continue
            }

            displays[key] = packetItemDisplay {
                location by this@ItemDisplays.location()
                metadata {
                    itemStack by model.item.createItemStack()
                    leftRotation by model.rotation
                }
            }.apply { spawn() }
        }
    }

    fun clear() {
        displays.values.forEach { it.despawn() }
        displays.clear()
    }

}
