package it.sgdc3.smartstorage.tileentity

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Math
import org.joml.Quaternionf
import org.joml.Vector3f
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.fakeentity.impl.FakeItemDisplay
import xyz.xenondevs.nova.world.fakeentity.impl.FakeTextDisplay

/**
 * How far the two displays float out of the block, measured from its middle — so anything over 0.5 is
 * outside the face.
 *
 * Half a pixel of clearance rather than none: an item drawn with the item-frame transform is not flat,
 * it keeps the depth of a generated model, and one sitting exactly on the face would z-fight with it.
 */
private const val ITEM_DEPTH = 0.53
private const val LABEL_DEPTH = 0.535

/**
 * The item sits just below the middle of the face and the count over its lower edge, the way every
 * barrel mod that shows its contents on the front arranges them.
 *
 * The item is not centred and not as large as it could be, and that is deliberate: the top strip of the
 * face has to stay clear, because that is where a locked barrel's padlock is stamped on the texture and
 * a display entity floating in front of it would hide it. At this scale the item reaches the fifth pixel
 * row from the top, which is exactly where the padlock ends.
 */
private const val ITEM_HEIGHT = -0.07
private const val LABEL_HEIGHT = -0.32

private const val ITEM_SCALE = 0.45f
private const val LABEL_SCALE = 0.5f

/**
 * How far away the two are still drawn, as a fraction of the 64 blocks a display entity renders at by
 * default — so 32 blocks for the item and 16 for the count.
 *
 * Cut down because a wall of barrels is not one display but a hundred, and a count that can only be read
 * from close up anyway costs the client nothing when it is out of range. The item stays visible further
 * out because the shape and colour of a wall is what you navigate by.
 */
private const val ITEM_VIEW_RANGE = 0.5f
private const val LABEL_VIEW_RANGE = 0.25f

/**
 * What a [StorageBarrel] shows on its front: the item it holds and how many of them.
 *
 * Two display entities rather than one, because they are updated at completely different rates — the
 * count changes with every transfer, the item only when the barrel changes what it stores — and a
 * display entity can have its metadata rewritten in place but not its kind of content. Both are Nova
 * fake entities, so they cost no server-side entity, are sent only to players who have the chunk, and
 * cannot be pushed, mined or picked up.
 *
 * Everything here touches packets, so it is main-thread only.
 */
class BarrelFace(private val pos: BlockPos, private val facing: BlockFace) {

    /**
     * A display entity shows its content facing south when it is not rotated, so this is the turn from
     * south onto [facing].
     *
     * It is the opposite sign of what [StorageHub] uses for its ports, and the difference is not a bug
     * in either: a port's *model* is authored pointing south, which the display's own half turn then
     * sends north, so a port needs the extra 180° that an item or a line of text does not.
     */
    private val rotation = Quaternionf().rotateY(Math.toRadians(-facing.yaw.toDouble()).toFloat())

    private var item: FakeItemDisplay? = null
    private var label: FakeTextDisplay? = null

    private var shownItem: ItemStack? = null
    private var shownLabel: Component? = null

    /**
     * Shows [stack] with [text] under it. Either may be null, which hides that half — an empty barrel
     * that has not been locked onto a type has nothing to say at all.
     *
     * Cheap to call every tick: it compares what is already on screen first, and a change to the count
     * rewrites one entity's metadata rather than respawning anything.
     */
    fun update(stack: ItemStack?, text: Component?) {
        if (stack != shownItem) {
            shownItem = stack
            applyItem(stack)
        }

        if (text != shownLabel) {
            shownLabel = text
            applyLabel(text)
        }
    }

    fun clear() {
        item?.remove()
        item = null
        label?.remove()
        label = null
        shownItem = null
        shownLabel = null
    }

    private fun applyItem(stack: ItemStack?) {
        if (stack == null) {
            item?.remove()
            item = null
            return
        }

        val existing = item
        if (existing != null) {
            existing.updateEntityData(true) { itemStack = stack }
            return
        }

        item = FakeItemDisplay(anchor(ITEM_HEIGHT, ITEM_DEPTH), true) { _, data ->
            data.itemStack = stack
            // the item-frame transform: the item lies flat rather than hovering as a 3D block
            data.itemDisplay = ItemDisplay.ItemDisplayTransform.FIXED
            data.billboardConstraints = Display.Billboard.FIXED
            data.leftRotation = rotation
            data.scale = Vector3f(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)
            data.viewRange = ITEM_VIEW_RANGE
        }
    }

    private fun applyLabel(text: Component?) {
        if (text == null) {
            label?.remove()
            label = null
            return
        }

        val existing = label
        if (existing != null) {
            existing.updateEntityData(true) { this.text = text }
            return
        }

        label = FakeTextDisplay(anchor(LABEL_HEIGHT, LABEL_DEPTH), true) { _, data ->
            data.text = text
            data.billboardConstraints = Display.Billboard.FIXED
            data.leftRotation = rotation
            data.scale = Vector3f(LABEL_SCALE, LABEL_SCALE, LABEL_SCALE)
            data.viewRange = LABEL_VIEW_RANGE
            data.alignment = TextDisplay.TextAlignment.CENTER
            // no panel behind the digits: the barrel's own front is the background
            data.defaultBackground = false
            data.backgroundColor = 0
            data.hasShadow = true
        }
    }

    /**
     * A point [up] above the middle of the block and [out] in front of it.
     *
     * The offsets go into the location rather than into the display's own translation on purpose: a
     * display applies its translation outside the rotation, so an offset put there would not follow
     * [facing] and the two halves would end up in front of the wrong side.
     */
    private fun anchor(up: Double, out: Double): Location = Location(
        pos.world,
        pos.x + 0.5 + facing.modX * out,
        pos.y + 0.5 + up,
        pos.z + 0.5 + facing.modZ * out
    )

}
