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
 * How far the displays float out of the block, measured from its middle — so anything over 0.5 is
 * outside the face.
 *
 * Half a pixel of clearance rather than none: an item drawn with the item-frame transform is not flat,
 * it keeps the depth of a generated model, and one sitting exactly on the face would z-fight with it.
 */
private const val ITEM_DEPTH = 0.53
private const val LABEL_DEPTH = 0.535

/**
 * How far away a display is still drawn, as a fraction of the 64 blocks one renders at by default — so
 * 32 blocks for an item and 16 for a count.
 *
 * Cut down because a wall of barrels is not one display but a hundred, and a count that can only be read
 * from close up anyway costs the client nothing when it is out of range. The items stay visible further
 * out because the shape and colour of a wall is what you navigate by.
 */
private const val ITEM_VIEW_RANGE = 0.5f
private const val LABEL_VIEW_RANGE = 0.25f

/**
 * Where one cell of the front sits and how large it is drawn: [side] across the face from its middle,
 * [itemUp] and [textUp] above it.
 */
private class Cell(
    val side: Double,
    val itemUp: Double,
    val itemScale: Float,
    val textUp: Double,
    val textScale: Float
)

/**
 * The face of a barrel with one thing to say, which is every barrel that compacts nothing: one item just
 * below the middle and the count over its lower edge, the way every barrel mod that shows its contents
 * on the front arranges them.
 *
 * ## Why the gap between them is what it is
 *
 * A sprite that fills its 16×16 was landing on the digits. The item's own box is as tall as its scale and
 * centred on its anchor, so its lower edge reaches 0.225 below the figure below — and an item whose
 * artwork runs to the edge of its sprite fills that box, while one drawn with padding only looked as
 * though there were room to spare. The clearance came out of the count, which was dropped to the band
 * under the recess rather than kept inside it.
 *
 * ## What this was laid out against
 *
 * These figures were arrived at by eye, before the texture was measured for [STACKED_TOP], and against a
 * reading of the padlock as a strip across the whole top of the face rather than the badge in its corner
 * that it is. Both are why the item sits lower and smaller than it has to and the count ends up outside
 * the window a compacting barrel's cells all fit inside. It is left alone because it is the face every
 * barrel already in a world is wearing, not because it is where the measurements would put it.
 */
private val SOLO = Cell(side = 0.0, itemUp = -0.04, itemScale = 0.45f, textUp = -0.37, textScale = 0.5f)

/**
 * The densest rung of a compacting barrel, at the top of the four rows that make up a compacting face.
 *
 * ## The window these four rows live in
 *
 * Measured off `block/storage_barrel.png` rather than guessed at. The dark recess is rows 3-12 and
 * columns 2-13 of a 16×16, so it spans 0.3125 above the middle of the block to 0.3125 below it and
 * 0.375 either side. One pixel of the frame is borrowed at the top and bottom — rows 2 and 13, which are
 * dark border and not the light band below it — giving 0.75 to fill and a hard stop at ±0.375.
 *
 * An item is as tall as its scale and a line of text a little over a fifth of its own, both centred on
 * their anchors, so the rows come out at 0.375…0.075, 0.055…-0.006, -0.026…-0.296 and -0.316…-0.3655,
 * with two hundredths of a block between them. There is nothing spare at either end.
 *
 * ## The padlock
 *
 * It is a badge in the top right — rows 0-4, columns 10-13 — not a strip across the whole top, which is
 * what makes room for an item this tall in the middle. The top item still reaches a quarter of a pixel
 * into its left edge, which is the price of centring it rather than nudging it off the middle of a face
 * that is symmetrical everywhere else.
 */
private val STACKED_TOP = Cell(side = 0.0, itemUp = 0.225, itemScale = 0.30f, textUp = 0.0245, textScale = 0.27f)

private const val LOWER_ITEM_UP = -0.161
private const val LOWER_ITEM_SCALE = 0.27f
private const val LOWER_TEXT_UP = -0.341
private const val LOWER_TEXT_SCALE = 0.22f

/**
 * Middle to middle of the two lower cells. Their items are [LOWER_ITEM_SCALE] wide, so this leaves an
 * eighth of a block between them and their outer edges four hundredths inside the recess.
 */
private const val LOWER_PITCH = 0.40

/**
 * Something a barrel is showing on its front: an item, and how much of it.
 */
data class FaceCell(val stack: ItemStack, val text: Component)

/**
 * What a [StorageBarrel] shows on its front: what it holds and how much of it, one cell per rung of its
 * compaction ladder.
 *
 * A barrel that compacts nothing has one cell and looks exactly as it always did. One that compacts has
 * up to three: the densest across the top and the lesser ones side by side beneath it, densest on the
 * left, so an iron barrel reads as a block over an ingot and a nugget with their counts under them.
 *
 * Two displays per cell rather than one, because they are updated at completely different rates — a
 * count changes with every transfer, an item only when the barrel changes what it stores — and a display
 * can have its metadata rewritten in place but not its kind of content. All of them are Nova fake
 * entities, so they cost no server-side entity, are sent only to players who have the chunk, and cannot
 * be pushed, mined or picked up.
 *
 * Everything here touches packets, so it is main-thread only.
 */
class BarrelFace(private val pos: BlockPos, private val facing: BlockFace) {

    /**
     * A display shows its content facing south when it is not rotated, so this is the turn from south
     * onto [facing].
     *
     * It is the opposite sign of what [StorageHub] uses for its ports, and the difference is not a bug
     * in either: a port's *model* is authored pointing south, which the display's own half turn then
     * sends north, so a port needs the extra 180° that an item or a line of text does not.
     */
    private val rotation = Quaternionf().rotateY(Math.toRadians(-facing.yaw.toDouble()).toFloat())

    private val items = ArrayList<FakeItemDisplay>(EXPOSED_TIERS)
    private val labels = ArrayList<FakeTextDisplay>(EXPOSED_TIERS)

    private var shown: List<FaceCell> = emptyList()

    /**
     * Shows [cells], densest first. An empty list hides the front altogether, which is what an empty
     * barrel that has not been locked onto a type has to say.
     *
     * Cheap to call every tick: a change to what a cell holds rewrites one display's metadata. Only a
     * change to *how many* cells there are costs anything, because a display's position is fixed when it
     * is spawned — and that happens when a compacting barrel crosses a whole block, not on every
     * transfer.
     */
    fun update(cells: List<FaceCell>) {
        if (cells.size != shown.size) {
            clear()
            spawn(cells)
            shown = cells
            return
        }

        for ((index, cell) in cells.withIndex()) {
            val was = shown[index]
            if (cell.stack != was.stack)
                items[index].updateEntityData(true) { itemStack = cell.stack }
            if (cell.text != was.text)
                labels[index].updateEntityData(true) { text = cell.text }
        }

        shown = cells
    }

    fun clear() {
        items.forEach(FakeItemDisplay::remove)
        items.clear()
        labels.forEach(FakeTextDisplay::remove)
        labels.clear()
        shown = emptyList()
    }

    private fun spawn(cells: List<FaceCell>) {
        // indexed rather than zipped: a layout that came back short would silently drop the cells past
        // it, and [update] would then be addressing displays that were never spawned
        val layouts = layout(cells.size)

        for ((index, cell) in cells.withIndex()) {
            val layout = layouts[index]

            items += FakeItemDisplay(anchor(layout.side, layout.itemUp, ITEM_DEPTH), true) { _, data ->
                data.itemStack = cell.stack
                // the item-frame transform: the item lies flat rather than hovering as a 3D block
                data.itemDisplay = ItemDisplay.ItemDisplayTransform.FIXED
                data.billboardConstraints = Display.Billboard.FIXED
                data.leftRotation = rotation
                data.scale = Vector3f(layout.itemScale, layout.itemScale, layout.itemScale)
                data.viewRange = ITEM_VIEW_RANGE
            }

            labels += FakeTextDisplay(anchor(layout.side, layout.textUp, LABEL_DEPTH), true) { _, data ->
                data.text = cell.text
                data.billboardConstraints = Display.Billboard.FIXED
                data.leftRotation = rotation
                data.scale = Vector3f(layout.textScale, layout.textScale, layout.textScale)
                data.viewRange = LABEL_VIEW_RANGE
                data.alignment = TextDisplay.TextAlignment.CENTER
                // no panel behind the digits: the barrel's own front is the background
                data.defaultBackground = false
                data.backgroundColor = 0
                data.hasShadow = true
            }
        }
    }

    /**
     * Where each of [cells] cells goes.
     *
     * One keeps the arrangement a barrel has always had. More than one puts the first across the top and
     * spreads the rest along the bottom, centred on the middle of the face however many there are — so a
     * barrel down to two rungs shows the second one under the first rather than off to the left.
     */
    private fun layout(cells: Int): List<Cell> {
        if (cells <= 1)
            return if (cells == 1) listOf(SOLO) else emptyList()

        val lower = cells - 1
        return buildList(cells) {
            add(STACKED_TOP)
            for (index in 0..<lower) {
                add(
                    Cell(
                        side = (index - (lower - 1) / 2.0) * LOWER_PITCH,
                        itemUp = LOWER_ITEM_UP,
                        itemScale = LOWER_ITEM_SCALE,
                        textUp = LOWER_TEXT_UP,
                        textScale = LOWER_TEXT_SCALE
                    )
                )
            }
        }
    }

    /**
     * A point [up] above the middle of the block, [out] in front of it and [side] to the right of it as
     * the face is read — so a negative [side] is the viewer's left.
     *
     * The offsets go into the location rather than into the display's own translation on purpose: a
     * display applies its translation outside the rotation, so an offset put there would not follow
     * [facing] and the cells would end up in front of the wrong side.
     */
    private fun anchor(side: Double, up: Double, out: Double): Location = Location(
        pos.world,
        pos.x + 0.5 + facing.modX * out + facing.modZ * side,
        pos.y + 0.5 + up,
        pos.z + 0.5 + facing.modZ * out - facing.modX * side
    )

}
