package it.sgdc3.smartstorage.util

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.joml.Math
import org.joml.Matrix4d
import org.joml.Vector3d
import xyz.xenondevs.nova.util.add
import xyz.xenondevs.nova.util.pitch
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.world.block.HitboxCuboid
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Rotates a pair of block-local points, written as if they pointed south, onto [face].
 *
 * Block-local means 0..1 across the block — a model's 0..16 divided by 16 — so a box can be copied
 * straight out of the model that draws it.
 *
 * Both vectors are consumed: joml transforms in place, so pass fresh ones.
 */
internal fun Block.boxTowards(a: Vector3d, b: Vector3d, face: BlockFace): Pair<Location, Location> {
    val origin = Vector3d(0.5, 0.5, 0.5)
    val transform = Matrix4d()
        .translate(origin)
        .rotateX(Math.toRadians(face.pitch.toDouble()))
        .rotateY(-Math.toRadians(face.yaw.toDouble()))
        .translate(origin.negate())

    val first = location.add(a.mulPosition(transform))
    val second = location.add(b.mulPosition(transform))
    // a rotation can swap which corner is the lower one on any axis, and a hitbox wants min then max
    return Location(world, min(first.x, second.x), min(first.y, second.y), min(first.z, second.z)) to
        Location(world, max(first.x, second.x), max(first.y, second.y), max(first.z, second.z))
}

/** Where a cable arm's cross-section starts, and how wide it is: the model's 6.75..9.25, over 16. */
private const val ARM_MIN = 6.75 / 16.0
private const val ARM_SIZE = 2.5 / 16.0

/**
 * Nova's own hitboxes for a cube core of half-size [coreHalf] around the block centre and an arm towards
 * each of [faces], matching the cable model.
 *
 * Nova backs each with an interaction entity and turns a hit on one into a hit on the block, so mining
 * runs Minecraft's own timer — hardness, tool and crack animation included — which a virtual hitbox
 * could never do. The price is the shape: an interaction entity is square across the horizontal plane,
 * so an arm lying sideways is a row of small cubes rather than one long box.
 */
internal fun armHitboxes(coreHalf: Double, faces: Collection<BlockFace>): List<HitboxCuboid> = buildList {
    val coreMin = 0.5 - coreHalf
    add(HitboxCuboid(coreMin, coreMin, coreMin, coreHalf * 2, coreHalf * 2))

    val length = 0.5 - coreHalf
    for (face in faces) {
        when (face) {
            BlockFace.UP -> add(HitboxCuboid(ARM_MIN, 0.5 + coreHalf, ARM_MIN, ARM_SIZE, length))
            BlockFace.DOWN -> add(HitboxCuboid(ARM_MIN, 0.0, ARM_MIN, ARM_SIZE, length))
            else -> {
                val start = if (face.modX + face.modZ > 0) 0.5 + coreHalf else 0.0
                // overlapping cubes rather than a gap: the last one ends flush with the block edge
                val count = ceil(length / ARM_SIZE).toInt()
                for (i in 0..<count) {
                    val along = start + (length - ARM_SIZE) * i / max(1, count - 1)
                    add(
                        if (face.modX != 0) HitboxCuboid(along, ARM_MIN, ARM_MIN, ARM_SIZE, ARM_SIZE)
                        else HitboxCuboid(ARM_MIN, ARM_MIN, along, ARM_SIZE, ARM_SIZE)
                    )
                }
            }
        }
    }
}
