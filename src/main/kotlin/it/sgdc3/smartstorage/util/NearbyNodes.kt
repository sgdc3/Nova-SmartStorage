package it.sgdc3.smartstorage.util

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import xyz.xenondevs.commons.collections.enumMap
import xyz.xenondevs.nova.util.CubeFaceSet
import xyz.xenondevs.nova.util.forEachNonNull
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.format.NetworkState

/**
 * The nodes against all six sides of [block], as an ordinary map with only the sides that have one.
 *
 * Nova answers with a fixed six-slot map holding nulls; everything here asks "what is on this side" or
 * "what is around me", and a map that simply has no entry for an empty side answers both.
 */
internal fun NetworkState.nearbyNodes(block: Block): Map<BlockFace, NetworkNode> {
    val nodes = enumMap<BlockFace, NetworkNode>()
    getNearbyNodes(block, CubeFaceSet.ALL).forEachNonNull { face, node -> nodes[face] = node }
    return nodes
}
