package it.sgdc3.smartstorage.network

import it.sgdc3.smartstorage.storage.ItemType
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidType

/**
 * In what order a storage system asks its providers, and nothing else.
 *
 * Separated from [StorageNetworkGroup] because the two answer different questions. The group decides
 * *whether* to move anything — is the system online, is the lock held, is the provider list still the
 * one this tick built — and that needs half of Nova to exist. This decides *where* it goes, and needs
 * nothing but a list. Every rule here is one that has been got wrong at least once, and getting it wrong
 * has twice meant items appearing out of nothing, so it is worth being able to state them one at a time
 * and check them.
 *
 * Everything here assumes [it.sgdc3.smartstorage.storage.StorageLock] is already held: it calls straight
 * into providers, which is what that lock guards.
 */
internal object Routing {

    /**
     * Puts [providers] in the order the network should use, highest priority first, keeping one of any
     * group that turns out to be the same storage.
     *
     * The sort comes before the deduplication, and that ordering is the whole point rather than an
     * accident: when a player has reached the same chest from two sides of a connector and set the two
     * sides differently, the one that survives has to be the one they raised. Deduplicating first would
     * keep whichever happened to be enumerated first.
     *
     * See [NetworkProvider.storageIdentity] for why two providers over one piece of storage happen at
     * all, and what counting it twice costs.
     */
    fun <T : NetworkProvider> order(providers: Sequence<T>): List<T> {
        val seen = HashSet<Any>()
        return providers
            .sortedByDescending(NetworkProvider::priority)
            .filter { seen.add(it.storageIdentity) }
            .toList()
    }

    /**
     * Stores up to [amount] items of [type] and returns how many were **left over**.
     *
     * Two passes, and the first is what keeps a system tidy: a provider that already holds this type
     * takes it before an empty one is opened, so pushing cobblestone at a sorted wall of barrels does
     * not scatter it into whichever happened to be free. Within a pass the order is [order]'s, so a
     * player's priorities decide the rest.
     */
    fun insert(providers: List<StorageProvider>, type: ItemType, amount: Long): Long {
        var left = amount

        for (provider in providers) {
            if (left <= 0L) break
            if (provider.holds(type))
                left -= provider.insert(type, left)
        }
        for (provider in providers) {
            if (left <= 0L) break
            left -= provider.insert(type, left)
        }

        return left
    }

    /**
     * Removes up to [amount] items of [type] and returns how many were actually removed.
     *
     * Backwards through [providers], so the lowest priority storage is drained first. That is what makes
     * "high priority fills first and empties last" true, and it is what lets a player say "keep the iron
     * in the drive bays and let the chests take the overflow": the chests only ever hold what is
     * spilling over at that moment.
     */
    fun extract(providers: List<StorageProvider>, type: ItemType, amount: Long): Long {
        var extracted = 0L

        for (i in providers.indices.reversed()) {
            if (extracted >= amount) break
            extracted += providers[i].extract(type, amount - extracted)
        }

        return extracted
    }

    /**
     * The same as [insert], for fluids: providers already holding this fluid before empty ones, so a
     * bucket is not split across two half-empty cells. Returns what was left over.
     */
    fun insertFluid(providers: List<FluidProvider>, type: FluidType, amount: Long): Long {
        var left = amount

        for (provider in providers) {
            if (left <= 0L) break
            if (provider.holdsFluid(type))
                left -= provider.insertFluid(type, left)
        }
        for (provider in providers) {
            if (left <= 0L) break
            left -= provider.insertFluid(type, left)
        }

        return left
    }

    /**
     * The same as [extract], for fluids. Returns how much was actually removed.
     */
    fun extractFluid(providers: List<FluidProvider>, type: FluidType, amount: Long): Long {
        var extracted = 0L

        for (i in providers.indices.reversed()) {
            if (extracted >= amount) break
            extracted += providers[i].extractFluid(type, amount - extracted)
        }

        return extracted
    }

}
