package it.sgdc3.smartstorage.util

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.joml.Math
import org.joml.Matrix4d
import org.joml.Vector3d
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.BlockBreak
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.add
import xyz.xenondevs.nova.util.pitch
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.world.block.hitbox.VirtualHitbox
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

/**
 * A hitbox that breaks the block at [block] when left-clicked.
 *
 * Blocks whose backing state has no shape of its own cannot be mined the usual way, so this stands in
 * for it — and the substitution is not free: the break is immediate, with no mining animation and no
 * regard for hardness, because Minecraft only runs its mining timer against a real block. The same
 * trade Nova's own cables make.
 */
internal fun destructionHitbox(block: Block, from: Location, to: Location): VirtualHitbox =
    VirtualHitbox(from, to).apply {
        setQualifier { player, _ -> player.gameMode != GameMode.ADVENTURE }
        addLeftClickHandler { player, _ ->
            val ctx = Context.intention(BlockBreak)
                .param(BlockBreak.BLOCK, block)
                .param(BlockBreak.SOURCE_PLAYER, player)
                .build()
            BlockUtils.breakBlockNaturally(ctx)
        }
    }
