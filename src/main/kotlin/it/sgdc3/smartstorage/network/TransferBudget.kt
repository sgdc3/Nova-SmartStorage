package it.sgdc3.smartstorage.network

import java.util.concurrent.atomic.AtomicLong

/**
 * How much a storage interface will still move this tick.
 *
 * It exists because Nova will not do this for us, and the reason is worth writing down. A fluid or item
 * network's transfer rate is the *lowest* rate among its bridges — its cables — and there is no floor
 * under that: `default_transfer_rate: -1` means unlimited, and a network built with no cable at all
 * keeps it. An interface placed straight against a tank is exactly that network, and fluid networks tick
 * every tick by default, so before this the whole system emptied into the tank in a single tick.
 *
 * So the throttle has to live on the interface. What it buys is that a storage interface is a *slow*
 * bridge to the outside world until a player pays to make it faster, which is the shape this kind of
 * block should have anyway.
 *
 * ## Per direction, deliberately
 *
 * There is one of these per resource *and per direction*, rather than one shared pool. Nova's fluid
 * distributor gives to the consumers first and then takes the same total back from the providers, and
 * **throws** if the providers come up short. A face configured both ways is both, so a shared pool would
 * let the giving half spend what the taking half had already promised — and the shortfall would be paid
 * out as fluid that never came from anywhere.
 *
 * ## What "per tick" means
 *
 * Refilled from the interface's own tick, which is every tick; consumed by network ticks, which happen
 * on their own schedule. So this is a ceiling on what one *network* tick may move, and with Nova's
 * defaults that is once a tick for fluids and once a second for items.
 */
internal class TransferBudget {

    private val remaining = AtomicLong()

    /**
     * The most that may still move. Never negative: a partial claim can only ever be rounded down.
     */
    fun available(): Long = remaining.get().coerceAtLeast(0L)

    /**
     * How much of [amount] the budget allows, which is all of it until it is not.
     */
    fun allow(amount: Long): Long = minOf(amount, available())

    /**
     * Records what actually moved. Called with the real figure rather than the allowance, so a transfer
     * that turned out smaller than permitted does not cost the budget the difference.
     */
    fun spend(amount: Long) {
        if (amount > 0L)
            remaining.addAndGet(-amount)
    }

    fun refill(amount: Long) = remaining.set(amount)

}
