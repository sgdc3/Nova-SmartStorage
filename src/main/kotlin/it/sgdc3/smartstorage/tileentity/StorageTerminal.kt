package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.MenuContent
import it.sgdc3.smartstorage.gui.MenuTracker
import it.sgdc3.smartstorage.registry.GuiTextures
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.block.Block
import org.bukkit.inventory.ItemType
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.dsl.NormalSplitWindowDsl
import xyz.xenondevs.invui.dsl.anvilWindow
import xyz.xenondevs.invui.dsl.item
import xyz.xenondevs.invui.dsl.with
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.ui.overlay.guitexture.DefaultGuiTextures
import xyz.xenondevs.nova.ui.overlay.guitexture.getTitle
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.world.block.NovaBlockState

/**
 * The window into a storage network: search, sort, take out, put in.
 */
class StorageTerminal(
    block: Block,
    state: NovaBlockState,
    data: Compound
) : AbstractTerminal(block, state, data) {

    private val menus = MenuTracker<StorageTerminalMenu>()

    override val isViewed: Boolean
        get() = menus.isViewed

    override val menu = menus.menu(GuiTextures.STORAGE_TERMINAL) { StorageTerminalMenu(this) }

    inner class StorageTerminalMenu(private val host: NormalSplitWindowDsl) : MenuContent {

        private val player = host.viewer

        private val filter = searchState()
        private val sortMode = sortState()

        /** 8 columns x 5 rows in the terminal itself, 8 x 3 in the search window — see [openSearch] */
        private val content = contentProvider(player, filter, sortMode, columns = 8, visibleSlots = 40)
        private val searchContent = contentProvider(player, filter, sortMode, columns = 8, visibleSlots = 24)

        override val gui = contentGui(
            content,
            "x x x x x x x x u",
            "x x x x x x x x s",
            "x x x x x x x x o",
            "x x x x x x x x f",
            "x x x x x x x x d",
            "i i i i i i i i n"
        ) {
            's' by searchButton()
            'o' by sortButton(sortMode)
            'f' by clearFilterButton(filter)
            'n' by networkItem
            'i' by depositInventory.with(depositBackground())
        }

        init {
            // load-bearing since the tile entity stopped rebuilding the index for nobody: this is what
            // makes the first look current. The gui is bound to the provider rather than to a snapshot
            // of it, so refreshing as the window opens is enough — on every opening, since Nova keeps a
            // closed window around for a while and hands the same one back.
            host.onOpen { refreshEntries() }
        }

        private fun searchButton(): Item = item {
            itemProvider by ItemBuilder(ItemType.COMPASS).setName(
                Component.translatable("menu.smartstorage.terminal.search", NamedTextColor.GRAY).withoutPreFormatting()
            )
            onClick {
                if (clickType.isLeftClick) {
                    player.playClickSound()
                    openSearch()
                }
            }
        }

        /**
         * Opens an anvil window whose text field feeds the filter, with the results taking the place of
         * the player inventory — the same shape Nova's own item explorer uses.
         */
        private fun openSearch() {
            val window = anvilWindow(player) {
                // Nova's own search background, so the anvil stops looking like an anvil
                title by DefaultGuiTextures.SEARCH.getTitle()
                // Four rows and three rows of results. InvUI requires a split window's lower gui to be
                // exactly 9x4 — it *is* the player inventory, three rows and the hotbar — while Nova's
                // search background frames only the three. The hotbar row sits below the panel, so it
                // is left empty and carries the one button that has to be reachable.
                lowerGui by contentGui(
                    searchContent,
                    "x x x x x x x x u",
                    "x x x x x x x x d",
                    "x x x x x x x x f",
                    ". . . . . . . . b"
                ) {
                    'f' by clearFilterButton(filter)
                    'b' by backButton()
                }
                text.subscribe(filter::set)
            }

            menu.register(window)
            window.open()
        }

        private fun backButton(): Item = item {
            itemProvider by ItemBuilder(ItemType.BARRIER).setName(
                Component.translatable("menu.smartstorage.terminal.back", NamedTextColor.GRAY).withoutPreFormatting()
            )
            onClick {
                if (clickType.isLeftClick) {
                    player.playClickSound()
                    host.window.get().open()
                }
            }
        }

    }

}
