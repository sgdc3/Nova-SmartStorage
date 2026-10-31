package it.sgdc3.smartstorage.gui

import org.bukkit.entity.Player
import xyz.xenondevs.invui.dsl.NormalSplitWindowDsl
import xyz.xenondevs.invui.dsl.WindowDsl
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.nova.registry.RegistryEntry
import xyz.xenondevs.nova.ui.overlay.guitexture.GuiTexture
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.TileEntityMenu
import java.util.*

/**
 * What a tile entity's main window shows: the upper half of it, built once per viewer.
 */
internal interface MenuContent {

    val gui: Gui

}

/**
 * The menus a tile entity has handed out, so it can redraw them and can tell whether anyone is looking.
 *
 * Nova used to keep this list itself; since 0.25 a tile entity's [TileEntityMenu] only opens and closes
 * windows, and the two questions the old container answered — "redraw every menu" and "is there one at
 * all" — are asked of this instead.
 *
 * Menus are held weakly. Nova caches a viewer's window for a while after it is closed and then lets it
 * go, and a menu nobody can reopen is not one worth redrawing. Synchronized because some redraws are
 * asked for from a network tick rather than from the server thread.
 */
internal class MenuTracker<M : MenuContent> {

    private val menus = Collections.newSetFromMap(WeakHashMap<M, Boolean>())
    private val viewers = HashSet<Player>()

    /**
     * Whether any of these menus is open in front of somebody right now — the gate for work that only
     * an open menu would show.
     */
    val isViewed: Boolean
        get() = synchronized(this) { viewers.isNotEmpty() }

    fun forEach(action: (M) -> Unit) {
        val snapshot = synchronized(this) { menus.toList() }
        snapshot.forEach(action)
    }

    private fun track(window: WindowDsl, menu: M) {
        synchronized(this) { menus += menu }
        window.onOpen { synchronized(this@MenuTracker) { viewers += window.viewer } }
        window.onClose { synchronized(this@MenuTracker) { viewers -= window.viewer } }
    }

    /**
     * The [TileEntityMenu] of a tile entity whose main window shows a [MenuContent] made by [create].
     *
     * Each viewer gets their own, and Nova keeps it for a minute after it is closed, so coming back to a
     * menu finds it as it was left — a terminal still searching for what it was searching for.
     */
    context(tileEntity: TileEntity)
    fun menu(texture: RegistryEntry.Nova<GuiTexture>, create: NormalSplitWindowDsl.() -> M): TileEntityMenu =
        TileEntityMenu.cachedWindow(texture) {
            val menu = create()
            track(this, menu)
            upperGui by menu.gui
        }

}
