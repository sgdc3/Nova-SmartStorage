package it.sgdc3.smartstorage.storage

import it.sgdc3.smartstorage.SmartStorage
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage

/**
 * A ladder of items that are the same material at different densities — nugget, ingot, block.
 *
 * [tiers] runs from the smallest upwards, and [perStep] holds how many of each tier make one of the
 * next: for iron that is `[nugget, ingot, block]` and `[9, 9]`. Everything a compacting barrel does is
 * arithmetic on those two lists.
 */
internal class Compaction(
    val tiers: List<ItemType>,
    private val perStep: List<Int>
) {

    /** The densest form, which is what a compacting barrel stores. */
    val top: ItemType
        get() = tiers.last()

    /**
     * How many of the smallest tier make one of [tier]. One for the smallest itself, 81 for an iron
     * block. This is the unit everything is counted in while it is inside the barrel.
     */
    fun unitsOf(tier: ItemType): Long {
        var units = 1L
        for ((index, candidate) in tiers.withIndex()) {
            if (candidate == tier)
                return units
            if (index < perStep.size)
                units *= perStep[index]
        }
        return 0L
    }

    /**
     * Splits [units] of the smallest tier into the largest pieces that will hold it, densest first.
     *
     * Used when a barrel has to hand a remainder back: forty iron nuggets come out as four ingots and
     * four nuggets rather than as forty nuggets, because that is what the player would have done with
     * them anyway.
     */
    fun split(units: Long): List<ItemStack> {
        val stacks = ArrayList<ItemStack>()

        for ((tier, count) in breakdown(units)) {
            // one stack per stack size, since this is handed to an inventory rather than to a network
            val size = tier.maxStackSize
            var whole = count
            while (whole > 0L) {
                val take = minOf(whole, size.toLong()).toInt()
                stacks += tier.createStack(take)
                whole -= take
            }
        }

        return stacks
    }

    /**
     * The whole of [units] as it would be if it were all one tier, for every tier that gets at least
     * one — so eighty-one iron nuggets read as *one block, nine ingots and eighty-one nuggets*.
     *
     * Not a decomposition. [breakdown] says what eighty-one units break into and answers "one block";
     * this says what they are *worth* at each density, and all three lines are the same iron. It is
     * what a player asking "how much iron is in there" means, and what a pipe asking for ingots can
     * actually have.
     */
    fun atEachTier(units: Long): List<Pair<ItemType, Long>> =
        tiers.asReversed()
            .map { tier -> tier to units / unitsOf(tier) }
            .filter { (_, count) -> count > 0L }

    /**
     * [units] split into the largest pieces that hold it, densest first, skipping the tiers that come
     * to nothing — 1234 iron nuggets break into fifteen blocks, two ingots and one nugget, which add
     * up to the whole and no more.
     *
     * The counting [atEachTier] does not do: this is for handing material back, where every piece is a
     * different piece.
     */
    fun breakdown(units: Long): List<Pair<ItemType, Long>> {
        val counts = ArrayList<Pair<ItemType, Long>>(tiers.size)
        var left = units

        for (tier in tiers.asReversed()) {
            val worth = unitsOf(tier)
            val whole = left / worth
            if (whole <= 0L)
                continue

            left -= whole * worth
            counts += tier to whole
        }

        return counts
    }

}

/**
 * Every compaction ladder the server knows about, worked out from its own recipes.
 *
 * ## Why recipes and not a table
 *
 * A written table would cover iron and copper and be wrong about everything a plugin adds. The server
 * already knows that nine iron ingots make a block, because somebody registered the recipe, and it
 * knows the same about whatever else is installed. Reading it back is the only version of this that
 * stays right.
 *
 * ## What counts as a compaction
 *
 * A recipe qualifies when it takes four or nine of one item and nothing else, and produces exactly one
 * of something — **and** the reverse exists, one of that something back into four or nine of the first.
 *
 * The reverse is the part that matters. Four planks make a crafting table and four quartz make a quartz
 * block, and neither can be undone: compacting into those would quietly destroy the ability to use the
 * material as anything else. Requiring the pair to be reversible is what keeps a compacting barrel from
 * being a shredder.
 */
@Init(stage = InitStage.POST_WORLD)
internal object Compactions {

    /**
     * The group sizes a compaction can come in: a full 2x2 or a full 3x3, and nothing else. A recipe
     * taking six of something is not a density step, it is a recipe.
     */
    private val STEPS = setOf(4, 9)

    private val ladders = HashMap<ItemType, Compaction>()

    @InitFun
    private fun build() {
        // Every "N of one thing make one of another", and every "one of a thing makes N back". They
        // have to be gathered separately and then matched, which is the whole of the reversibility
        // test: reading a single recipe both ways only ever proves that the recipe exists.
        val packs = HashMap<ItemType, MutableList<Pair<ItemType, Int>>>()
        val unpacks = HashMap<ItemType, MutableList<Pair<ItemType, Int>>>()

        for (recipe in Bukkit.recipeIterator()) {
            val (ingredient, ingredients) = uniformIngredient(recipe) ?: continue
            val result = ItemType.of(recipe.result) ?: continue
            if (result == ingredient)
                continue

            val amount = recipe.result.amount
            when {
                ingredients in STEPS && amount == 1 ->
                    packs.getOrPut(ingredient) { ArrayList() } += result to ingredients

                ingredients == 1 && amount in STEPS ->
                    unpacks.getOrPut(ingredient) { ArrayList() } += result to amount
            }
        }

        // One rung per base: the candidate that can be undone. An iron ingot has two "four or nine of
        // me make one of those" recipes — the block, and an iron trapdoor — and only the block gives
        // the ingots back. Without this the ladder for iron climbed into trapdoors.
        val reversible = HashMap<ItemType, Pair<ItemType, Int>>()
        for ((base, candidates) in packs) {
            // The densest step wins, rather than whichever recipe the server happened to hand over
            // first. Sophisticated Storage tries 3x3 before 2x2 for the same reason: an item with a
            // reversible pair of both would otherwise compact differently depending on iteration
            // order, which is the kind of difference that shows up as one server behaving unlike
            // another and nobody able to say why.
            val step = candidates
                .filter { (compact, count) -> unpacks[compact]?.any { it.first == base && it.second == count } == true }
                .maxByOrNull { it.second }

            if (step != null)
                reversible[base] = step
        }

        for (start in reversible.keys) {
            // start from the bottom only, so each ladder is built once and whole
            if (reversible.values.any { it.first == start })
                continue

            val tiers = arrayListOf(start)
            val steps = ArrayList<Int>()
            var current = start

            while (true) {
                val step = reversible[current] ?: break
                // a cycle would be a recipe pair that compacts something into itself by a longer route
                if (step.first in tiers)
                    break

                tiers += step.first
                steps += step.second
                current = step.first
            }

            if (steps.isEmpty())
                continue

            val ladder = Compaction(tiers, steps)
            for (tier in tiers)
                ladders[tier] = ladder
        }

        // The deepest one is named because it is the claim worth checking at a glance: two tiers is a
        // pair anybody would guess, three means the chaining actually joined them up.
        val distinct = ladders.values.distinct()
        val deepest = distinct.maxByOrNull { it.tiers.size }
        SmartStorage.logger.info(
            "Compaction: ${distinct.size} ladders over ${ladders.size} items" +
                (deepest?.let { ", deepest ${it.tiers.joinToString(" -> ") { tier -> tier.stack.type.name }}" } ?: "")
        )
    }

    /**
     * The ladder [type] belongs to, or null if nothing compacts it and it compacts into nothing.
     */
    fun of(type: ItemType): Compaction? = ladders[type]

    /**
     * The smallest whole ingredient count a recipe is made of, if it is one item repeated four or nine
     * times and nothing else.
     */
    private fun uniformIngredient(recipe: Recipe): Pair<ItemType, Int>? {
        val stacks: List<ItemStack> = when (recipe) {
            is ShapedRecipe -> {
                val map = recipe.choiceMap
                recipe.shape.flatMap { row -> row.map { key -> map[key] } }
                    .map { choice -> choice?.sole() ?: return null }
            }

            is ShapelessRecipe -> recipe.choiceList.map { it.sole() ?: return null }
            else -> return null
        }

        // 1 as well as 4 and 9: the same reading serves the recipe that unpacks a block back into nine
        // ingots, which is one ingredient rather than nine
        if (stacks.size != 1 && stacks.size !in STEPS)
            return null

        val first = ItemType.of(stacks.first()) ?: return null
        if (stacks.any { ItemType.of(it) != first })
            return null

        return first to stacks.size
    }

    /**
     * The one item this ingredient can be, or null if it is anything else.
     *
     * Null covers two cases and both should be refused. An ingredient that accepts a *tag* — any plank,
     * any log — is not one material compacting into another, whatever the counts look like. And an
     * ingredient shape this does not recognise is one whose contents cannot be read, which is not the
     * same as one that is empty.
     */
    private fun RecipeChoice.sole(): ItemStack? = when (this) {
        is RecipeChoice.MaterialChoice -> choices.singleOrNull()?.let(ItemStack::of)
        is RecipeChoice.ExactChoice -> choices.singleOrNull()
        else -> null
    }

}
