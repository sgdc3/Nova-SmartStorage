package it.sgdc3.smartstorage.network

import xyz.xenondevs.nova.util.CubeFaceSet
import xyz.xenondevs.nova.world.block.tileentity.network.node.EndPointDataHolder

/**
 * The [EndPointDataHolder] that makes a tile-entity part of a storage network.
 *
 * Unlike item, fluid and energy holders this one carries no per-face configuration: a device either is
 * on the network or it isn't, so there is nothing for a wrench to cycle through.
 */
class StorageHolder(
    val endPoint: StorageEndPoint,
    override val allowedFaces: CubeFaceSet = CubeFaceSet.ALL
) : EndPointDataHolder
