package ksl.examples.general.supplychain

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.cost.CostParams
import ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Cost-formulation demo 2 — tuning the cost parameters.
 *
 * Shows the canonical pattern for setting non-default cost rates:
 * construct a [CostParams] with named arguments and pass it to the
 * [DefaultMultiEchelonCostFormulation].  Every parameter has a
 * default so the modeler only specifies what they want to change.
 *
 * Item-level cost data lives on the [ItemType] (`unitCost`) — it's
 * not part of `CostParams` because it's per-item rather than
 * per-network.
 *
 * **Time-unit reminder**: continuous rates (`carryingRate`,
 * `backorderRate`) are denominated in 1/(modeler-chosen time
 * unit).  This model interprets one simulated time unit as one day
 * (lead times in `day` units), so the carrying rate `0.10/year`
 * needs to be expressed as `0.10/365 = 0.000274` per day for the
 * emitted holding-cost Response to come out in `$/day`.  Or, leave
 * it as `0.10` and interpret the holding-cost Response as `$/year`
 * — the framework doesn't enforce one or the other; the modeler
 * picks and stays consistent.  This example picks the second
 * convention.
 */
fun main() {
    val m = Model("CostDemo-CustomParams")
    val sc = SupplyChainModel(m, name = "SC")
    val net = MultiEchelonNetwork(
        sc, name = "Net",
        transportStrategy = TransportStrategy.PerIHPTimeBased,
    )

    // Per-item unit cost (drives holding, in-transit, shipment-builder
    // holding lines).  Lives on the ItemType, not on CostParams.
    val item = net.addItemType("Widget", ConstantRV(1.0)).apply {
        unitCost = 12.50
    }

    val warehouse = net.addInventoryHoldingPoint("W")
    warehouse.addReorderPointReorderQuantityInventory(
        item, reorderPoint = 4, reorderQty = 20, initialOnHand = 20,
    )
    net.attachToExternalSupplier(warehouse, ConstantRV(3.0))

    val r1 = net.addInventoryHoldingPoint("R1")
    r1.addReorderPointReorderQuantityInventory(
        item, reorderPoint = 2, reorderQty = 5, initialOnHand = 10,
    )
    net.attachToSupplier(warehouse, r1, ConstantRV(1.0))
    net.attachDemandGenerator(
        r1, item, ExponentialRV(1.5, streamNum = 11), name = "DG-R1",
    )

    // Custom cost parameters — every field has a sensible default;
    // override only what differs from those.  The convention picked
    // here: continuous rates per year, discrete-event costs in
    // dollars per event.
    val params = CostParams(
        carryingRate     = 0.15,    // 15% per year
        backorderRate    = 25.0,    // $25 per unit per year while backordered
        orderingCost     = 50.0,    // $50 per replenishment order
        unloadingCost    = 12.0,    // $12 per inbound shipment
        loadingCost      =  8.0,    // $8 per outbound shipment
        shippingCost     = 18.0,    // $18 per outbound shipment
        stockoutCost     =  5.0,    // $5 per stockout event
        unitShortageCost = 30.0,    // $30 per unit short
        esLoadingCost    = 40.0,    // $40 per ES-side outbound shipment
        // lostSaleCost left at default (0.0) — backloggable demands,
        // no lost sales in this scenario.
    )

    DefaultMultiEchelonCostFormulation(net, params)

    m.numberOfReplications = 10
    m.lengthOfReplication = 365.0
    m.lengthOfReplicationWarmUp = 30.0
    m.simulate()

    println("=== half-width summary report (with custom params) ===")
    m.simulationReporter.printHalfWidthSummaryReport()
}
