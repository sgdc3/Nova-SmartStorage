package it.sgdc3.smartstorage.network

import it.sgdc3.smartstorage.storage.SortMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The small shared vocabulary: what "online" means, when a network has fluid storage worth mentioning,
 * and the priority scale every device is measured on.
 *
 * None of it is complicated, and all of it is read by several places that would disagree quietly if it
 * ever changed under them.
 */
class StorageStatusTest {

    @Test
    fun `online is exactly having no reason not to be`() {
        assertTrue(StorageNetworkStatus(null, 5, 2).isOnline)

        for (reason in OfflineReason.entries)
            assertFalse(StorageNetworkStatus.offline(reason).isOnline, "$reason is not online")
    }

    @Test
    fun `an offline status still carries the counts, so a player can see what it found`() {
        val status = StorageNetworkStatus.offline(OfflineReason.NO_ENERGY, devices = 12, cells = 4)

        assertEquals(12, status.devices)
        assertEquals(4, status.cells)
        assertEquals(OfflineReason.NO_ENERGY, status.offlineReason)
    }

    @Test
    fun `every offline reason can say why in the player's language`() {
        for (reason in OfflineReason.entries)
            assertTrue(reason.localizationKey.startsWith("menu.smartstorage.status."), "$reason")
    }

    /**
     * A system of storage cells being told it holds 0 of 0 buckets is a line about something that is not
     * there, so the menus ask this before printing one.
     */
    @Test
    fun `a system has fluid storage only when something can hold fluid`() {
        assertFalse(StorageTotals.EMPTY.hasFluidStorage)
        assertFalse(StorageTotals(3, 8, 500L, 1000L, 0L, 0L).hasFluidStorage)
        assertTrue(StorageTotals(0, 0, 0L, 0L, 0L, 16_000L).hasFluidStorage, "empty tanks are still tanks")
    }

    @Test
    fun `the empty totals are actually empty`() {
        with(StorageTotals.EMPTY) {
            assertEquals(0, usedTypes)
            assertEquals(0, totalTypes)
            assertEquals(0L, usedCount)
            assertEquals(0L, totalCount)
            assertEquals(0L, usedFluid)
            assertEquals(0L, totalFluid)
        }
    }

    /**
     * The scale has to stay non-negative because the menus draw it with Nova's numbered GUI item, whose
     * models start at zero — and the default has to sit in the middle so there is as much room to demote
     * something as to promote it.
     */
    @Test
    fun `the priority scale runs from zero and starts in the middle`() {
        assertEquals(0, PRIORITY_RANGE.first)
        assertEquals(100, PRIORITY_RANGE.last)
        assertTrue(DEFAULT_PRIORITY in PRIORITY_RANGE)
        assertEquals(PRIORITY_RANGE.first + PRIORITY_RANGE.last, DEFAULT_PRIORITY * 2, "the midpoint")
    }

    @Test
    fun `sort modes cycle back round`() {
        var mode = SortMode.AMOUNT
        repeat(SortMode.entries.size) { mode = mode.next() }

        assertEquals(SortMode.AMOUNT, mode, "stepping through all of them returns to the start")
        assertEquals(SortMode.NAME, SortMode.AMOUNT.next())
    }

    @Test
    fun `every sort mode has a name to show`() {
        for (mode in SortMode.entries)
            assertTrue(mode.localizationKey.startsWith("menu.smartstorage.terminal.sort."), "$mode")
    }

}
