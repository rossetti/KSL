package ksl.modeling.supplychain.cost

/**
 * Parameter bundle for a [DefaultMultiEchelonCostFormulation].  All
 * rate values are denominated in 1 / (modeler-chosen time unit) —
 * the framework performs **no time-unit conversion** and makes no
 * presumption about what the modeler's rate basis is.  The emitted
 * cost-line Responses are therefore reported in the modeler's
 * chosen time unit (a `carryingRate` of `0.10/year` produces a
 * holding cost in `$/year`; `0.10/day` produces it in `$/day`).
 *
 * Discrete-event $/event values are dimensionless with respect to
 * time and emit a per-replication total.
 *
 * @param carryingRate continuous-rate inventory carrying rate per
 *        unit per (modeler-chosen time unit).  Multiplies the
 *        time-weighted on-hand and on-order averages.
 * @param backorderRate continuous-rate backlog carrying rate per
 *        unit per (modeler-chosen time unit).  Multiplies the
 *        time-weighted backlog average.  Default 0.0 disables the
 *        continuous backorder-cost line.
 * @param orderingCost $ per replenishment-order event.
 * @param unloadingCost $ per inbound shipment.
 * @param loadingCost $ per outbound shipment.
 * @param shippingCost $ per outbound shipment.
 * @param stockoutCost $ per stockout event.
 * @param lostSaleCost $ per lost-sale event.
 * @param unitShortageCost $ per unit short.
 * @param esLoadingCost $ per outbound shipment from the external
 *        supplier.
 */
data class CostParams(
    val carryingRate: Double = 0.10,
    val backorderRate: Double = 0.0,
    val orderingCost: Double = 5.5,
    val unloadingCost: Double = 30.0,
    val loadingCost: Double = 40.0,
    val shippingCost: Double = 15.0,
    val stockoutCost: Double = 0.0,
    val lostSaleCost: Double = 0.0,
    val unitShortageCost: Double = 0.0,
    val esLoadingCost: Double = 40.0,
)
