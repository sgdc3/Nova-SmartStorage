package it.sgdc3.smartstorage

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.mockbukkit.mockbukkit.MockBukkit

/**
 * Marks a test that needs a server behind it.
 *
 * Almost everything here does, because almost everything here is built on [org.bukkit.inventory.ItemStack]
 * — and an `ItemStack` with no server answers nothing. It cannot say how big a stack of it is, it cannot
 * compare itself to another one, and it cannot clone itself, because all three go through the item
 * factory the server owns. There is no way to unit-test a storage cell without one.
 *
 * ## What the server is, and what it is not
 *
 * It is MockBukkit, standing in for Paper. That has one consequence worth stating rather than burying in
 * the build file: MockBukkit ships the game's own data tables, so it only works against the Paper it was
 * built for, and its newest build is one minor behind the one this addon compiles against. The tests
 * therefore run on Paper 26.1.2 while production runs on 26.2.
 *
 * That is a real gap and it is worth being clear about which side of it these tests are on. They cover
 * this addon's arithmetic — how many items fit in a cell, which provider gets asked first, what happens
 * when a rollback comes up short. They do not cover Paper's behaviour, and they are not evidence that
 * anything works on 26.2. The API they lean on is stack sizes, similarity and cloning, which has been
 * the same for many versions; [HarnessTest] asserts that much rather than assuming it.
 */
@ExtendWith(ServerBackedExtension::class)
annotation class ServerBacked

/**
 * Boots MockBukkit once for the whole run.
 *
 * Once, not per class: `MockBukkit.mock()` throws if a server is already up, and standing one up per
 * class would throw away the item registry between them for no benefit. JUnit builds an extension per
 * class, so "once" has to live somewhere that outlives the instance — which is what the companion is.
 *
 * Deliberately not JUnit's own root-context store, which would be the idiomatic place: every method that
 * puts a value in it is deprecated as of JUnit 6, and a flag and a shutdown hook do the same job without
 * betting on where that API lands next.
 */
class ServerBackedExtension : BeforeAllCallback {

    override fun beforeAll(context: ExtensionContext) = boot()

    private companion object {

        private var booted = false

        @Synchronized
        fun boot() {
            if (booted)
                return

            MockBukkit.mock()
            booted = true

            // tidy rather than necessary — the JVM is about to end either way, but a server left standing
            // is the kind of thing that only stops being harmless once somebody reuses the forked JVM
            Runtime.getRuntime().addShutdownHook(Thread { MockBukkit.unmock() })
        }

    }

}
