package it.sgdc3.smartstorage.util

import org.bukkit.block.BlockFace
import org.junit.jupiter.api.Test
import xyz.xenondevs.nova.world.block.HitboxCuboid
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [armHitboxes] is the only thing a player can aim at on a cable, so a gap in it is a stretch of cable
 * that cannot be mined or clicked, and a box poking out of the block is one that grabs clicks meant for
 * the neighbour. Neither would fail anywhere but in game.
 */
class HitboxesTest {

    private val faces = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)

    /** The extent of [boxes] along the axis [face] points on, as (min, max). */
    private fun extent(boxes: List<HitboxCuboid>, face: BlockFace): Pair<Double, Double> = when {
        face.modX != 0 -> boxes.minOf { it.minX } to boxes.maxOf { it.maxX }
        face.modY != 0 -> boxes.minOf { it.minY } to boxes.maxOf { it.maxY }
        else -> boxes.minOf { it.minZ } to boxes.maxOf { it.maxZ }
    }

    @Test
    fun `nothing connected is the core alone`() {
        val boxes = armHitboxes(coreHalf = 2.0 / 16.0, emptyList())
        assertEquals(listOf(HitboxCuboid(6.0 / 16.0, 6.0 / 16.0, 6.0 / 16.0, 4.0 / 16.0, 4.0 / 16.0)), boxes)
    }

    @Test
    fun `every box stays inside the block`() {
        for (coreHalf in listOf(2.0 / 16.0, 4.0 / 16.0)) {
            for (box in armHitboxes(coreHalf, faces)) {
                for (v in listOf(box.minX, box.minY, box.minZ))
                    assertTrue(v >= -1e-9, "$box starts outside the block")
                for (v in listOf(box.maxX, box.maxY, box.maxZ))
                    assertTrue(v <= 1.0 + 1e-9, "$box ends outside the block")
            }
        }
    }

    @Test
    fun `an arm runs from the core to the block edge without a gap`() {
        for (coreHalf in listOf(2.0 / 16.0, 4.0 / 16.0)) {
            for (face in faces) {
                val arm = armHitboxes(coreHalf, listOf(face)).drop(1)
                val (min, max) = extent(arm, face)
                val positive = face.modX + face.modY + face.modZ > 0
                assertEquals(if (positive) 0.5 + coreHalf else 0.0, min, 1e-9, "$face starts in the wrong place")
                assertEquals(if (positive) 1.0 else 0.5 - coreHalf, max, 1e-9, "$face ends in the wrong place")

                // consecutive cubes along the arm must touch or overlap
                val spans = arm.map { extent(listOf(it), face) }.sortedBy { it.first }
                spans.zipWithNext().forEach { (a, b) -> assertTrue(b.first <= a.second + 1e-9, "$face has a gap") }
            }
        }
    }

}
