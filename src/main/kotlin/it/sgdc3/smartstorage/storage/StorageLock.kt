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
 *
 * ## The one thing that would not survive that change
 *
 * This lock and InvUI's own monitors are taken in **both orders**, and only the ticker keeps that from
 * mattering. A network transfer holds this lock and then reaches into InvUI, because every provider
 * notifies its open menus once it has moved something; a menu click goes the other way, taking whatever
 * InvUI holds during a click and then this lock, because that is what taking an item out of a terminal
 * or pulling a cell from a drive bay does.
 *
 * Two threads doing those at once is the textbook deadlock, and the only reason it cannot happen is the
 * paragraph above: the tick blocks the server thread, so there is never a second thread in the other
 * order. Anyone making network ticks genuinely concurrent has to break one of the two directions —
 * most likely by moving the menu notifications out of the guarded section — and not merely widen this
 * lock's coverage.
 */
internal object StorageLock : ReentrantLock()
