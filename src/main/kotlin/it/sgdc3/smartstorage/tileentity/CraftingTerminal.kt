package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.gui.CRAFTING_GRID_SIZE
import it.sgdc3.smartstorage.gui.CraftingGrid
import it.sgdc3.smartstorage.registry.Blocks.CRAFTING_TERMINAL
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
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.ui.overlay.guitexture.DefaultGuiTextures
import xyz.xenondevs.nova.util.component.adventure.withoutPreFormatting
import xyz.xenondevs.nova.util.playClickSound
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass

private val MAX_BULK_CRAFTS by CRAFTING_TERMINAL.config.entry<Int>("max_bulk_crafts")

/**
 * A terminal with a crafting grid that restocks itself from the network.
 *
 * The grid itself is [CraftingGrid], because the wireless terminal has one too. What belongs to the
 * block is that the nine slots are *persisted*: walk away from this one and the half-assembled recipe is
 * still sitting in it.
 */
class CraftingTerminal(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : AbstractTerminal(pos, state, data) {

    private val craftingInventory = storedInventory("crafting", CRAFTING_GRID_SIZE)

    private val grid = CraftingGrid(
        craftingInventory,
        { pos.world },
        { storageNetwork },
        { MAX_BULK_CRAFTS },
        ::refreshEntries
    )

    @TileEntityMenuClass
    inner class CraftingTerminalMenu(player: Player) : IndividualTileEntityMenu(player, GuiTextures.CRAFTING_TERMINAL) {

        private val filter = searchState()
        private val sortMode = sortState()

        /** 6 columns x 5 rows here, since the crafting grid takes the right third; 8 x 4 in the search window */
        private val content = contentProvider(player, filter, sortMode, columns = 6, visibleSlots = 30)
        private val searchContent = contentProvider(player, filter, sortMode, columns = 8, visibleSlots = 24)

        override val gui = contentGui(
            content,
            "x x x x x x c c c",
            "x x x x x x c c c",
            "x x x x x x c c c",
            "x x x x x x u r d",
            "x x x x x x s o f",
            "i i i i i i i i n"
        ) {
            'c' by craftingInventory
            'r' by grid.resultItem()
            's' by searchButton()
            'o' by sortButton(sortMode)
            'f' by clearFilterButton(filter)
            'n' by networkItem
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

        private fun openSearch() {
            val window = anvilWindow(player) {
                // Nova's own search background, so the anvil stops looking like an anvil
                title by DefaultGuiTextures.SEARCH.component
                // three rows, as in the storage terminal: Nova's search background draws three, and the
                // fourth sat unframed in the gap below the panel
                lowerGui by contentGui(
                    searchContent,
                    "x x x x x x x x u",
                    "x x x x x x x x d",
                    "x x x x x x x x b"
                ) {
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
