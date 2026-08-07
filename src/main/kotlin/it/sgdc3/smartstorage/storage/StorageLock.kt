package it.sgdc3.smartstorage.storage

import java.util.concurrent.locks.ReentrantLock

/**
 * Guards every read and write of storage cell contents.
 *
 * Storage data is touched from places that do not share a thread: network group ticks and
 * [xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory] calls run
 * off the main thread, while GUI clicks and tile entity ticks run on it.
 *
 * A single global lock is deliberate: it removes any chance of a lock ordering bug, and it cannot be
 * lost when Nova rebuilds the transient
 * [xyz.xenondevs.nova.world.block.tileentity.network.Network] objects.
 *
 * Two things are worth being honest about rather than leaving implied.
 *
 * First, the guarded sections are **not** all cheap. They walk cell maps, which are small, but they also
 * reach into Bukkit containers and into another addon's `NetworkedInventory`. Holding one global lock
 * across those is a deliberate simplification, not a claim that they are fast.
 *
 * Second, on Paper today the parallelism this guards against is mostly theoretical: Nova drives the
 * network tick from a synchronous scheduler task that blocks the server thread until every network
 * group has finished, so a tile entity tick or a menu click cannot overlap a network worker. That is an
 * implementation detail of the ticker, not a contract — so the lock stays. It is cheap when uncontended
 * and it is the only thing that would keep this correct if Nova ever ticked networks off the main
 * thread's critical path.
 */
internal object StorageLock : ReentrantLock()
