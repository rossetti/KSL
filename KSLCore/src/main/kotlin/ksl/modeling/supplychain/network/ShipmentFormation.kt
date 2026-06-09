package ksl.modeling.supplychain.network

import ksl.modeling.supplychain.transport.DemandLoadBuilder

/**
 * Per-edge shipment-formation policy passed to a
 * [MultiEchelonNetwork] attach method.  When a non-null
 * [ShipmentFormation] is supplied, the network installs a
 * [DemandLoadBuilder] on the supplier node keyed by the customer.
 * Outbound demands flow through the builder and are bundled into
 * loads per the [option].
 *
 * Only meaningful when the supplier node was added with
 * `enableShipmentFormation = true` (which installs a
 * [ksl.modeling.supplychain.transport.TimeBasedLoadCarrier] for the
 * node) and the network's [TransportStrategy] is
 * [TransportStrategy.PerIHPTimeBased].  Other strategies do not
 * support per-edge formation in this phase.
 *
 * @property option which forming rule the builder runs.  See
 *           [DemandLoadBuilder.LoadFormingOption].
 * @property countLimit limit used by
 *           [DemandLoadBuilder.LoadFormingOption.COUNT]
 * @property weightLimits min/max limits used by
 *           [DemandLoadBuilder.LoadFormingOption.WEIGHT]; null for
 *           the other options
 * @property cubeLimits min/max limits used by
 *           [DemandLoadBuilder.LoadFormingOption.CUBE]; null for
 *           the other options
 *
 * @see DemandLoadBuilder
 * @see MultiEchelonNetwork.attachToSupplier
 */
data class ShipmentFormation(
    val option: DemandLoadBuilder.LoadFormingOption,
    val countLimit: Int = 0,
    val weightLimits: Pair<Double, Double>? = null,
    val cubeLimits: Pair<Double, Double>? = null,
) {
    init {
        when (option) {
            DemandLoadBuilder.LoadFormingOption.COUNT ->
                require(countLimit > 0) { "countLimit must be > 0 for COUNT formation" }
            DemandLoadBuilder.LoadFormingOption.WEIGHT ->
                require(weightLimits != null) {
                    "weightLimits must be set for WEIGHT formation"
                }
            DemandLoadBuilder.LoadFormingOption.CUBE ->
                require(cubeLimits != null) {
                    "cubeLimits must be set for CUBE formation"
                }
            DemandLoadBuilder.LoadFormingOption.ALWAYS,
            DemandLoadBuilder.LoadFormingOption.RULE,
            DemandLoadBuilder.LoadFormingOption.NONE -> { /* no extra params */ }
        }
    }
}
