package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.registry.GuiTextures
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.dsl.anvilWindow
import xyz.xenondevs.invui.dsl.item
import xyz.xenondevs.invui.dsl.with
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.nova.ui.overlay.guitexture.DefaultGuiTextures
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass

/**
 * The window into a storage network: search, sort, take out, put in.
 */
class StorageTerminal(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : AbstractTerminal(pos, state, data) {

    @TileEntityMenuClass
    inner class StorageTerminalMenu(player: Player) : IndividualTileEntityMenu(player, GuiTextures.STORAGE_TERMINAL) {

        private val filter = searchState()
        private val sortMode = sortState()

        /** 8 columns x 5 rows in the terminal itself, 8 x 4 in the search window's lower gui */
        private val content = contentProvider(player, filter, sortMode, columns = 8, visibleSlots = 40)
        private val searchContent = contentProvider(player, filter, sortMode, columns = 8, visibleSlots = 32)

        override val gui = contentGui(
            content,
            "x x x x x x x x u",
            "x x x x x x x x s",
            "x x x x x x x x o",
            "x x x x x x x x f",
            "x x x x x x x x d",
            "i i i i i i i i ."
        ) {
            's' by searchButton()
            'o' by sortButton(sortMode)
            'f' by clearFilterButton(filter)
            'i' by depositInventory.with(depositBackground())
        }

        init {
            // load-bearing since the tile entity stopped rebuilding the index for nobody: this is what
            // makes the first look current. The gui is bound to the provider rather than to a snapshot
            // of it, so refreshing after building it is enough.
            refreshEntries()
        }

        private fun searchButton(): Item = item {
            itemProvider by ItemBuilder(Material.COMPASS).setName(
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
                title by DefaultGuiTextures.SEARCH.component
                lowerGui by contentGui(
                    searchContent,
                    "x x x x x x x x u",
                    "x x x x x x x x d",
                    "x x x x x x x x f",
                    "x x x x x x x x b"
                ) {
                    'f' by clearFilterButton(filter)
                    'b' by backButton()
                }
                text.subscribe(filter::set)
            }

            menuContainer.registerWindow(window)
            window.open()
        }

        private fun backButton(): Item = item {
            itemProvider by ItemBuilder(Material.BARRIER).setName(
                Component.translatable("menu.smartstorage.terminal.back", NamedTextColor.GRAY).withoutPreFormatting()
            )
            onClick {
                if (clickType.isLeftClick) {
                    player.playClickSound()
                    openWindow()
                }
            }
        }

    }

}
