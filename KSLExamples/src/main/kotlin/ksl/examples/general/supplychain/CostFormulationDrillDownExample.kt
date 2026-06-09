package ksl.examples.general.supplychain

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.cost.CostLine
import ksl.modeling.supplychain.cost.CostParams
import ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
import ksl.modeling.supplychain.cost.InventoryCostCalculator
import ksl.modeling.supplychain.cost.NodeTier
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Cost-formulation demo 3 — drilling down past the network-level
 * rollups.
 *
 * The four `total*Response` accessors on
 * [MultiEchelonNetwork] expose tier-level and per-line rollups, but
 * the formulation also retains the finer-grained data:
 *   - per-(tier, line) — `formulation.byTierAndLineResponse(tier, line)`
 *   - per-(node, item, line) — iterate `formulation.calculators` and
 *     read each calculator's `lineResponses[line]`
 *
 * This example shows both drill-down paths.  Useful for sensitivity
 * studies ("which IHP contributes most to ordering cost?") and for
 * custom reporting beyond the half-width summary.
 */
fun main() {
    val m = Model("CostDemo-DrillDown")
    val sc = SupplyChainModel(m, name = "SC")
    val net = MultiEchelonNetwork(
        sc, name = "Net",
        transportStrategy = TransportStrategy.PerIHPTimeBased,
    )

    val widget = net.addItemType("Widget", ConstantRV(1.0)).apply { unitCost = 10.0 }
    val gadget = net.addItemType("Gadget", ConstantRV(1.0)).apply { unitCost = 25.0 }

    val warehouse = net.addInventoryHoldingPoint("W")
    warehouse.addReorderPointReorderQuantityInventory(
        widget, reorderPoint = 4, reorderQty = 20, initialOnHand = 20,
    )
    warehouse.addReorderPointReorderQuantityInventory(
        gadget, reorderPoint = 2, reorderQty = 10, initialOnHand = 10,
    )
    net.attachToExternalSupplier(warehouse, ConstantRV(3.0))

    val retailers = (1..3).map { i ->
        val r = net.addInventoryHoldingPoint("R$i")
        r.addReorderPointReorderQuantityInventory(
            widget, reorderPoint = 2, reorderQty = 5, initialOnHand = 10,
        )
        r.addReorderPointReorderQuantityInventory(
            gadget, reorderPoint = 1, reorderQty = 3, initialOnHand = 5,
        )
        net.attachToSupplier(warehouse, r, ConstantRV(1.0))
        net.attachDemandGenerator(r, widget,
            ExponentialRV(1.5, streamNum = 10 + 2 * i), name = "DG-R$i-W")
        net.attachDemandGenerator(r, gadget,
            ExponentialRV(2.5, streamNum = 11 + 2 * i), name = "DG-R$i-G")
        r
    }

    val params = CostParams(carryingRate = 0.15, orderingCost = 50.0)
    val formulation = DefaultMultiEchelonCostFormulation(net, params)

    m.numberOfReplications = 5
    m.lengthOfReplication = 365.0
    m.lengthOfReplicationWarmUp = 30.0
    m.simulate()

    // ---- Per-(tier, line) drill-down --------------------------------
    println("=== per-(tier, line) breakdown (last-replication values) ===")
    println("(Tier × Line)        IHP            CD             ES")
    for (line in CostLine.all) {
        val ihp = formulation.byTierAndLineResponse(NodeTier.IHP, line)?.value ?: 0.0
        val cd  = formulation.byTierAndLineResponse(NodeTier.CD,  line)?.value ?: 0.0
        val es  = formulation.byTierAndLineResponse(NodeTier.ES,  line)?.value ?: 0.0
        if (ihp != 0.0 || cd != 0.0 || es != 0.0) {
            println("  %-18s %12.2f   %12.2f   %12.2f".format(line.displayName, ihp, cd, es))
        }
    }

    // ---- Per-(node, item, line) drill-down --------------------------
    // Walk the formulation's calculators and pick out
    // InventoryCostCalculators (one per (IHP, item)) to see which
    // (node, item) pairs contribute most to a given line.
    println()
    println("=== per-(node, item) Ordering cost ===")
    for (calc in formulation.calculators.filterIsInstance<InventoryCostCalculator>()) {
        val ordering = calc.lineResponses[CostLine.Ordering]?.value ?: 0.0
        if (ordering > 0.0) {
            println("  ${calc.source.name}  ordering = %.2f".format(ordering))
        }
    }

    println()
    println("=== per-(node, item) Holding cost ===")
    for (calc in formulation.calculators.filterIsInstance<InventoryCostCalculator>()) {
        val holding = calc.lineResponses[CostLine.Holding]?.value ?: 0.0
        if (holding > 0.0) {
            println("  ${calc.source.name}  holding = %.2f".format(holding))
        }
    }
}
