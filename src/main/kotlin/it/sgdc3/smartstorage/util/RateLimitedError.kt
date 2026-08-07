package it.sgdc3.smartstorage.util

import it.sgdc3.smartstorage.SmartStorage
import java.util.concurrent.atomic.AtomicLong

/**
 * An error that prints in full the first time and sparingly after that, carrying a count of what it
 * swallowed.
 *
 * Every user of this is a duplication detector: a `take` that handed out more than the provider had, which
 * is a bug and never routine. Which is exactly why they cannot be a plain `logger.error`. They sit inside
 * a network tick, so the failure mode is not one line, it is twenty a second for as long as the bug lasts
 * — and an addon that fills a production disk while its owner is asleep has turned a storage bug into an
 * outage.
 *
 * The first occurrence is printed whole, because it is the one somebody will actually read. The rest are
 * counted and reported with the next one, so "how often" survives the throttling; suppressing the count as
 * well would be the other way to get this wrong.
 */
internal class RateLimitedError(private val everyMillis: Long = 60_000L) {

    private val suppressed = AtomicLong()
    private val nextAt = AtomicLong()

    /**
     * [message] is a lambda so that a suppressed call costs nothing but a clock read — these sit on the
     * hot path, and the string is the expensive part.
     */
    fun log(message: () -> String) {
        val now = System.currentTimeMillis()
        val due = nextAt.get()

        // Two guards, and both are needed: the first skips the common case without touching the clock
        // twice, the second settles the race between two network workers arriving together. Losing the
        // CAS means somebody else is printing this, so this call is the one that gets counted.
        if (now < due || !nextAt.compareAndSet(due, now + everyMillis)) {
            suppressed.incrementAndGet()
            return
        }

        val skipped = suppressed.getAndSet(0L)
        val text = message()
        SmartStorage.logger.error(
            if (skipped > 0L) "$text (and $skipped more like it since the last of these)" else text
        )
    }

}
