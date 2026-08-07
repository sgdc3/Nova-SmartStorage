package it.sgdc3.smartstorage.util

/**
 * Packs up to 32 connection flags into a single int, least significant bit first.
 *
 * Used to turn the storage cable's six boolean block state properties into the model id that
 * `tools/gen-cable-models.ps1` generated files for.
 */
internal fun encodeFlags(vararg flags: Boolean): Int {
    var result = 0
    for (i in flags.indices) {
        if (flags[i])
            result = result or (1 shl i)
    }
    return result
}
