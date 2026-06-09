package ksl.modeling.supplychain.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable description of a network's transport strategy, mirroring
 * the framework's `TransportStrategy` sealed class.
 *
 * | variant | builder behaviour |
 * |---|---|
 * | [SharedCarrier] | one shared `NoDelayDemandCarrier` for every leg |
 * | [PerIHPTimeBased] | each node owns its own `TimeBasedDemandCarrier`; per-edge transport times |
 * | [NetworkTimeBased] | one shared `TimeBasedNetworkDemandCarrier` keyed on `(filler, sender)` |
 *
 * A custom shared carrier is a v1 escape hatch (build the spec, then
 * post-mutate the network); only the three standard strategies are
 * describable in data.  Shipment formation requires [PerIHPTimeBased]
 * (validated in [validate]).
 */
@Serializable
sealed class TransportStrategySpec {

    /** One shared no-delay carrier for the whole network. */
    @Serializable
    @SerialName("shared")
    data object SharedCarrier : TransportStrategySpec()

    /** Per-node time-based carriers; per-edge transport times. */
    @Serializable
    @SerialName("perIHP")
    data object PerIHPTimeBased : TransportStrategySpec()

    /** One shared network-wide time-based carrier. */
    @Serializable
    @SerialName("network")
    data object NetworkTimeBased : TransportStrategySpec()
}
