package it.sgdc3.smartstorage.tileentity

import it.sgdc3.smartstorage.registry.Blocks.ENERGY_VALVE
import it.sgdc3.smartstorage.registry.Models
import org.bukkit.Bukkit
import xyz.xenondevs.cbf.Compound
import org.bukkit.block.BlockFace
import org.joml.Math
import org.joml.Quaternionf
import org.bukkit.entity.Player
import xyz.xenondevs.commons.collections.toEnumSet
import xyz.xenondevs.nova.addon.simpleupgrades.registry.UpgradeTypes
import xyz.xenondevs.nova.addon.simpleupgrades.storedEnergyHolder
import xyz.xenondevs.nova.addon.simpleupgrades.storedUpgradeHolder
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.add
import xyz.xenondevs.nova.util.pitch
import xyz.xenondevs.nova.util.yaw
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkEndPoint
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes.ENERGY
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkConnectionType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.holder.EnergyHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.holder.ItemHolder
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.model.FixedMultiModel
import xyz.xenondevs.nova.world.model.Model
import xyz.xenondevs.nova.world.item.NovaItem
import kotlin.math.max
import kotlin.math.min

private val MAX_ENERGY = ENERGY_VALVE.config.entry<Long>("max_energy")

/**
 * Ticks between two looks at *where* the machines are. Whether their outputs are full is checked every
 * tick — see [EnergyValve.handleTick].
 */
private val WATCH_TICKS by ENERGY_VALVE.config.entry<Int>("watch_ticks")

/**
 * The arm this addon draws when the tier is not one it has artwork for, and the one it draws between two
 * nodes wired straight to each other.
 */
private const val PLAIN_ARM = "plain"
private const val BASE_ARM = "basic"

/**
 * Whether Logistics is here at all. Its cable textures are what the tier arms name, and with it absent
 * every arm falls back to this addon's own — a missing texture is a worse answer than the wrong colour.
 */
private val LOGISTICS by lazy { Bukkit.getPluginManager().getPlugin("Logistics") != null }

/**
 * A tap on an energy cable that shuts while the machine beside it has nowhere to put its output.
 *
 * ## What it is for
 *
 * A Nova machine with a full output inventory does not stop paying for itself. The auto crafter's tick
 * is the plain example — it spends its energy first and only then asks whether the result will fit:
 *
 * ```
 * if (hasRecipe && energy >= energyPerTick) {
 *     energy -= energyPerTick
 *     if (++idleTime >= maxIdleTime) craft()   // and craft() checks canHold, and does nothing
 * }
 * ```
 *
 * So a machine whose output has backed up burns power for as long as nobody notices. This block is how
 * you notice for it.
 *
 * ## Why a valve and not a switch on the machine
 *
 * Nova has no notion of a machine being off: not redstone, not a flag, nothing in the tile entity layer
 * at all. Ticking is governed by the chunk, and `handleEnableTicking` is a notification rather than a
 * switch. The only two handles a neighbour exposes are its per-face connection config and its energy
 * buffer.
 *
 * The config is the wrong one to grab: it is a config the *player* also edits, so using it means
 * remembering what was there and putting it back, and fighting them if they change it meanwhile — the
 * trap that cables already taught this addon once. The buffer is the right one, but only because the
 * charge taken out of it is held rather than destroyed; see [settle].
 *
 * So a valve owns everything it touches. It sits in the cable run with power passing through it, and
 * when it shuts it shuts *its own* faces and takes the machine's remaining charge into escrow. Nothing
 * downstream is fed, nothing is destroyed, and no other block's settings are disturbed. The cost is that
 * the power has to be routed through it, which is how a tap works anywhere else.
 *
 * ## It shuts a side, not itself
 *
 * Every face is decided on its own. A valve with a cable on one side and two machines on the others goes
 * on feeding the cable and the machine that still has room, and stops only the one that has backed up —
 * so it is a pass-through first and a tap second. Cutting the whole block would make a single blocked
 * machine starve everything downstream of it, which is a worse fault than the waste it was built to fix.
 *
 * ## What it looks like
 *
 * A core with an arm towards every side it carries power to — the shape of a pipe fitting, not a block
 * standing beside one, because that is what it is. It borrows [StorageHub] for that.
 *
 * **Each arm wears the colour of the pipe it touches**, decided one face at a time, so a valve dropped
 * into an elite run looks like elite pipe and one between two tiers shows both. Two nodes wired straight
 * to each other get the base tier, which is what the shortest possible length of pipe between them would
 * have looked like.
 *
 * That is why the arms are display entities and the block model is the core alone. Six faces over six
 * tiers is more than any block state could hold; one model of each is enough once the entity does the
 * turning. It also took the valve from a hundred and thirty model files to ten.
 *
 * Every machine gets a nozzle, and **each nozzle carries its own side's answer**: lit while that machine
 * is being fed, dark while it is being held closed. The core then follows them — lit while at least one
 * nozzle is, dark when every side is shut, and dark as well when there is no machine at all, because
 * then there is nothing being governed.
 *
 * That is why the nozzles say it and not the block state. A hub has one lit/unlit state and its arms are
 * drawn with the cable's texture, so a dark arm beside bright ones is not something the state can
 * express; ports are display entities, one per face, and can differ. With three machines on one valve, a
 * single light could only ever say that *something* had stopped.
 *
 * ## What counts as "the output is full"
 *
 * Any neighbour that is an end point, holds items, and has at least one container the network may only
 * *take* from. That is what an output buffer is, by construction: a machine's result slots allow
 * extraction and refuse insertion, while its inputs do the opposite and its internal buffers allow both.
 *
 * Reading it that way means the valve works with any machine from any addon without knowing what it is —
 * there is no list of block types here and nothing to keep up to date. A machine with several outputs is
 * blocked only when *all* of them are full, since one free slot is still somewhere to put work.
 */
class EnergyValve(
    pos: BlockPos,
    state: NovaBlockState,
    data: Compound
) : StorageHub(pos, state, data) {

    private val upgradeHolder = storedUpgradeHolder(UpgradeTypes.ENERGY)

    /**
     * `BUFFER`, because a valve is a battery that happens to be small: it takes power in on one side and
     * gives it out on the other, and which side is which is decided by the cables, not by us.
     */
    private val energyHolder = storedEnergyHolder(MAX_ENERGY, upgradeHolder, NetworkConnectionType.BUFFER)

    /**
     * The arms, drawn as display entities so that each can carry the colour of the pipe on its own face.
     *
     * Six faces over six tiers is more than any block state could hold, so the block model is the core
     * alone and these are turned onto their faces at runtime — the same trade [StorageHub] already makes
     * for its ports, for the same reason.
     */
    private val armModel = FixedMultiModel()

    /**
     * The output buffers of each machine around the valve, remembered between scans.
     *
     * Split from the fullness check on purpose. Which sides have a machine changes only when somebody
     * builds something, and finding out costs a walk of the network state on its own thread; whether
     * those buffers are full changes constantly and costs a field read. Caching the first is what lets
     * the second run every tick.
     */
    @Volatile
    private var outputs: Map<BlockFace, List<NetworkedInventory>> = emptyMap()

    /**
     * The sides currently held closed, so the tick can tell a change from a repetition.
     */
    @Volatile
    private var shut: Set<BlockFace> = emptySet()

    /**
     * What the valve took out of each machine when it shut that side, held until it opens again.
     *
     * Per face and persisted, because a valve that forgot this over a restart would have destroyed
     * somebody's energy rather than borrowed it.
     *
     * Not in [energyHolder]: this is not the valve's own charge and must not be. A machine's buffer can
     * be larger than the valve's, and anything sitting in the holder is on the network and can be pulled
     * out by a cable — which would turn a loan into a theft. It is an escrow, and the only way out of it
     * is back where it came from.
     */
    private val held = CUBE_FACES.associateWith { face -> storedValue("held_${face.name}") { 0L } }

    override val portModel: NovaItem = Models.VALVE_PORT
    override val portModelOff: NovaItem = Models.VALVE_PORT_OFF

    /**
     * A nozzle per machine, lit while that machine is being fed and dark while it is being held closed.
     *
     * Per face, unlike every other hub here, and that is the point: a valve with three machines on it
     * has three answers, and one light for all of them could only say that *something* had stopped. The
     * core then follows the nozzles — see [handleTick].
     */
    override fun portModelFor(face: BlockFace): NovaItem =
        if (face in shut) Models.VALVE_PORT_OFF else Models.VALVE_PORT

    override fun handleDisable() {
        armModel.clear()
        super.handleDisable()
    }

    override suspend fun handleNetworkLoaded(state: NetworkState) = sync(state)

    override suspend fun handleNetworkUpdate(state: NetworkState) = sync(state)

    /**
     * Fullness every tick, topology on a timer.
     *
     * Every tick, and it has to be. A machine filling its output is not a change to the network's shape,
     * so Nova never tells us about it — and a scan once a second is a second of work a fast machine goes
     * on doing after it has run out of room. A block breaker with a full set of speed upgrades gets
     * through sixteen blocks in that window. What it costs to close that gap is one `isFull` per output
     * buffer per tick, because [outputs] already knows where they are.
     *
     * Stopping the machine does not need the network thread at all: it is the escrow in [settle] that
     * stops it, and that is a field on a neighbour, read and written here in step with its own tick. The
     * connection config does need the network thread, and follows a moment later — by which time the
     * machine has already stopped.
     */
    override fun handleTick() {
        if (serverTick % max(1, WATCH_TICKS) == 0)
            NetworkManager.queueWrite(pos.chunkPos, ::sync)

        val blocked = outputs
            .filterValues { buffers -> buffers.isNotEmpty() && buffers.all(NetworkedInventory::isFull) }
            .keys.toEnumSet()

        // Every tick, not only when the set changes: a machine the valve has cut may still be fed by
        // another cable the player ran to it, and a side called shut has to stay shut. Costs a map
        // lookup per shut side, which is nearly always none of them, and does nothing when there is
        // nothing left to take.
        for (face in blocked)
            settle(face, true)

        if (blocked == shut)
            return

        // Giving it back is the other direction and belongs to the moment a side opens: run every tick
        // it would be six lookups for nothing.
        for (face in CUBE_FACES) {
            if (face !in blocked)
                settle(face, false)
        }

        shut = blocked

        // The core says what the nozzles say: lit while at least one machine is still being fed, dark
        // when every one of them is held closed — and dark as well when there is no machine at all,
        // because then there is no nozzle lit and nothing being governed.
        setPowered(outputs.keys.any { it !in blocked })
        // the set of sides has not changed, only which of them is being served, so nothing else redraws
        refreshPortModels()
        NetworkManager.queueWrite(pos.chunkPos) { state -> applyFaces(state, blocked) }
    }

    /**
     * Finds the machines around the valve and points the arms and nozzles at them.
     *
     * Only the shape of things: what is where, not whether it is full. The tick above reads that off
     * [outputs] and needs no network state to do it.
     */
    private suspend fun sync(state: NetworkState) {
        val found = machineOutputs(state)
        outputs = found

        val blocked = shut
        applyFaces(state, blocked)

        // A shut side keeps its arm and its nozzle. The cable and the machine are both still there, and
        // taking either away would say they had gone rather than that this one side is being held closed.
        val arms = state.getConnectedNodes(this).row(ENERGY).keys.toEnumSet() + blocked

        // A face with a machine on it shows a nozzle, and a nozzle swallows the arm behind it whole —
        // same axis, and the flange is wider than the arm is. Drawing both would be two entities to see
        // one thing, so the arms are the faces the nozzles do not cover.
        val tiers = arms.filter { it !in found }.associateWith { face -> tierOn(state, face) }

        runTask {
            if (!isEnabled)
                return@runTask

            setArmFaces(arms)
            setPortFaces(found.keys)
            setPowered(found.keys.any { it !in blocked })
            refreshPortModels()
            applyArms(tiers)
        }
    }

    /**
     * Takes a shut machine's charge into escrow, or hands it back when the side opens.
     *
     * This is what makes the valve stop a machine *now* rather than in five seconds. Cutting the supply
     * alone leaves the machine to spend whatever it had buffered — a hundred ticks of it, for an auto
     * crafter — on work it cannot finish. Emptying the buffer instead would stop it at once and destroy
     * that energy, which is no better: over a long block the two waste exactly the same amount, and over
     * a short one destroying it is far worse, because the machine then has to fill up again before it
     * can resume.
     *
     * Holding it is what makes emptying it defensible. Nothing is spent and nothing is lost; the charge
     * moves into the valve, waits there, and goes back the moment the side opens. A machine stopped this
     * way resumes instantly, which is more than one left to drain itself can do.
     *
     * Idempotent in both directions, which is what lets the scan run on a timer without remembering what
     * it did last time: a side already emptied has nothing left to take, and a side with nothing in
     * escrow has nothing to give.
     */
    private fun settle(face: BlockFace, shut: Boolean) {
        val escrow = held.getValue(face)
        val holder = neighbourEnergy(face)

        if (shut) {
            val charge = holder?.energy ?: return
            if (charge <= 0L)
                return

            holder.energy = 0L
            escrow.set(escrow.get() + charge)
            return
        }

        var left = escrow.get()
        if (left <= 0L)
            return

        if (holder != null) {
            val give = min(left, holder.maxEnergy - holder.energy)
            if (give > 0L) {
                holder.energy += give
                left -= give
            }
        }

        // Whatever will not go back — the machine is gone, or smaller than it was — is put on the
        // network through the valve's own buffer rather than kept forever. What still does not fit stays
        // in escrow and is tried again on the next scan.
        if (left > 0L) {
            val give = min(left, energyHolder.maxEnergy - energyHolder.energy)
            if (give > 0L) {
                energyHolder.energy += give
                left -= give
            }
        }

        escrow.set(left)
    }

    /**
     * The energy buffer of whatever is on [face], read on the main thread from the world rather than
     * from a network snapshot — the charge has to be moved in step with its owner's tick.
     */
    private fun neighbourEnergy(face: BlockFace): EnergyHolder? {
        val node = WorldDataManager.getTileEntity(pos.advance(face)) as? NetworkEndPoint ?: return null
        return node.holders.filterIsInstance<EnergyHolder>().firstOrNull()
    }

    /**
     * Which pipe tier is on [face], as the name of an arm model.
     *
     * Read off the neighbour's block id rather than its class, so it costs nothing and needs no import
     * of somebody else's types: `logistics:elite_cable` is the elite tier and anything else is not a
     * tier we have artwork for.
     *
     * Two nodes wired straight to each other get the base tier, which is what a length of pipe between
     * them would have looked like at its plainest.
     */
    private fun tierOn(state: NetworkState, face: BlockFace): String {
        if (!LOGISTICS)
            return PLAIN_ARM

        val node = state.getNearbyNodes(pos, setOf(face))[face] as? TileEntity ?: return BASE_ARM
        val id = node.block.id
        if (id.namespace() != "logistics")
            return BASE_ARM

        val tier = id.value().removeSuffix("_cable")
        return if (tier in Models.VALVE_ARMS) tier else BASE_ARM
    }

    /**
     * Points an arm of the right colour at each face in [tiers]. Main thread only: it spawns display
     * entities.
     */
    private fun applyArms(tiers: Map<BlockFace, String>) {
        armModel.replaceModels(
            tiers.mapNotNullTo(HashSet()) { (face, tier) ->
                val item = Models.VALVE_ARMS[tier] ?: return@mapNotNullTo null

                Model(
                    item.createClientsideItemBuilder().get(),
                    pos.location.add(0.5, 0.5, 0.5),
                    // authored pointing south, like the ports, and turned the same way
                    leftRotation = Quaternionf()
                        .rotateY(Math.toRadians(180.0 - face.yaw).toFloat())
                        .rotateX(Math.toRadians(-face.pitch.toDouble()).toFloat())
                )
            }
        )
    }

    /**
     * Every side with a machine on it, and the buffers that machine can only be *taken* from.
     *
     * That is what an output buffer is, by construction: a machine's result slots allow extraction and
     * refuse insertion, while its inputs do the opposite and its internal buffers allow both. Reading it
     * that way means the valve works with any machine from any addon without knowing what it is — there
     * is no list of block types here and nothing to keep up to date.
     *
     * A neighbour with no output buffer at all is not a machine this valve has any business governing: a
     * cable, a battery or another valve simply gets fed, and takes no nozzle.
     */
    private fun machineOutputs(state: NetworkState): Map<BlockFace, List<NetworkedInventory>> {
        val machines = HashMap<BlockFace, List<NetworkedInventory>>()

        for ((face, node) in state.getNearbyNodes(pos, CUBE_FACES)) {
            if (node !is NetworkEndPoint)
                continue

            val buffers = ArrayList<NetworkedInventory>(1)

            for (holder in node.holders) {
                if (holder !is ItemHolder)
                    continue

                for ((inventory, allowed) in holder.containers) {
                    if (allowed == NetworkConnectionType.EXTRACT)
                        buffers += inventory
                }
            }

            if (buffers.isNotEmpty())
                machines[face] = buffers
        }

        return machines
    }

    /**
     * Shuts the faces in [blocked] and opens every other one.
     *
     * A closed face is not merely idle to Nova: its network builder **throws** on a face it still holds a
     * connection to whose type is `NONE`, and that exception takes down the build for every network of
     * every type. [NetworkState.handleEndPointAllowedFacesChange] is what stops that, and it is the same
     * protocol Nova's own side config menu follows — see [restrictItemFaces], which pays for this lesson
     * at greater length.
     */
    private suspend fun applyFaces(state: NetworkState, blocked: Set<BlockFace>) {
        for (face in CUBE_FACES) {
            val wanted =
                if (face in blocked) NetworkConnectionType.NONE else NetworkConnectionType.BUFFER

            if (energyHolder.connectionConfig[face] == wanted)
                continue

            energyHolder.connectionConfig[face] = wanted
            state.getNetwork(this, ENERGY, face)?.markDirty()
            state.handleEndPointAllowedFacesChange(this, ENERGY, face)
        }
    }

}
