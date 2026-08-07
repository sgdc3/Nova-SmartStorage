package it.sgdc3.smartstorage.storage

/**
 * One line of a terminal's item list: a type and how many of it the network holds.
 */
data class StorageEntry(val type: ItemType, val amount: Long)

/**
 * The orders a terminal can list its contents in.
 */
enum class SortMode(val localizationKey: String) {

    AMOUNT("menu.smartstorage.terminal.sort.amount"),
    NAME("menu.smartstorage.terminal.sort.name");

    fun next(): SortMode = entries[(ordinal + 1) % entries.size]

}
