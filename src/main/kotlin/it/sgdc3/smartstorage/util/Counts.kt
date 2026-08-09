package it.sgdc3.smartstorage.util

/**
 * The units a count can be shortened to, smallest first. Nothing beyond a billion, because nothing in
 * this addon reaches one: a barrel filled to its limit with the smallest rung of a ladder is about 1.3
 * million nuggets, and a whole wall of them is under a hundred million.
 */
private val UNITS = listOf(1_000L to "K", 1_000_000L to "M", 1_000_000_000L to "B")

/**
 * A count shortened to fit somewhere there is no room for it: 999 stays 999, 1234 becomes 1.2K, 18432
 * becomes 18K, and 1327104 becomes 1.3M.
 *
 * For block faces and other places measured in pixels, never for a menu — a barrel's own screen has room
 * for the real figure, and a player counting stock wants to be told 18432 rather than asked to guess
 * which of five hundred numbers 18K stands for.
 *
 * One decimal only below ten of a unit, which is what keeps the whole thing to four characters over the
 * range in [UNITS]. Past that it grows rather than lying, which is the right way round for a figure that
 * cannot occur.
 *
 * The value is truncated rather than rounded: 1999 reading as 2.0K would be a barrel claiming material
 * it does not have, and on a display whose whole job is to say what is inside, being short is the
 * harmless direction to be wrong in.
 */
fun abbreviate(count: Long): String {
    val magnitude = UNITS.lastOrNull { (unit, _) -> count <= -unit || count >= unit }
        ?: return count.toString()

    val (unit, suffix) = magnitude
    val whole = count / unit
    // tenths of the unit the whole part left behind, so 1234 is one K and two tenths
    val tenths = (count % unit) * 10 / unit

    return if (whole > -10L && whole < 10L && tenths != 0L)
        "$whole.${if (tenths < 0L) -tenths else tenths}$suffix"
    else
        "$whole$suffix"
}
