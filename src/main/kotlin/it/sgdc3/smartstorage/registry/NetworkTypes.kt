package it.sgdc3.smartstorage.registry

import it.sgdc3.smartstorage.SmartStorage
import it.sgdc3.smartstorage.network.StorageHolder
import it.sgdc3.smartstorage.network.StorageNetwork
import it.sgdc3.smartstorage.network.StorageNetworkGroup
import xyz.xenondevs.commons.provider.Provider
import xyz.xenondevs.nova.config.Configs
import xyz.xenondevs.nova.config.entry
import xyz.xenondevs.nova.config.node
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage

/**
 * `configs/config.yml` of this addon.
 */
internal val SMART_STORAGE_CONFIG = Configs[SmartStorage, "config"]

/**
 * How often a readout that nothing pushes to is rebuilt, in ticks.
 *
 * Shared rather than re-read per file because it is one answer to one question — how stale may a number
 * on a screen be — and three copies of it would be three knobs that only look like one. Everything that
 * shows figures it has to go and fetch uses it: both item terminals, the fluid terminal, the wireless
 * terminal and a connector's per-side slot counts.
 */
internal val TERMINAL_REFRESH_TICKS by SMART_STORAGE_CONFIG.node("terminal").entry<Int>("refresh_ticks")

/**
 * Registers `smartstorage:storage` as a fourth network type alongside Nova's energy, item and fluid
 * networks. Doing so hands us topology discovery, network splitting and merging, chunk load and unload
 * handling and asynchronous ticking for free.
 */
@Init(stage = InitStage.PRE_WORLD)
object NetworkTypes {

    val TICK_DELAY_PROVIDER: Provider<Int> = SMART_STORAGE_CONFIG.node("network").entry<Int>("tick_delay")

    val STORAGE = SmartStorage.registerNetworkType(
        "storage",
        ::StorageNetwork,
        ::StorageNetworkGroup,
        StorageNetwork::validateLocal,
        TICK_DELAY_PROVIDER,
        StorageHolder::class
    )

}
