package ksl.examples.general.supplychain

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Cost-formulation demo 1 — the canonical default-everything
 * pattern.  Build a small ES → 1 warehouse → 3 retailers network,
 * attach a [DefaultMultiEchelonCostFormulation] with all-default
 * [ksl.modeling.supplychain.cost.CostParams], simulate, and print
 * the half-width report.
 *
 * The default-parameter cost responses appear in the report under
 * names like "DefaultMultiEchelonCostFormulation:Total:Tier:IHP"
 * and "DefaultMultiEchelonCostFormulation:GrandTotal".  Users who
 * want shorter property-style access can still read
 * `net.totalCostResponse!!.value`, `net.totalIHPCostResponse!!.value`,
 * etc. — those properties delegate to the formulation's rollups.
 *
 * Try running this; the report shows confidence intervals for each
 * cost line as well as every per-(node, item) Response the
 * calculators own.
 */
fun main() {
    val m = Model("CostDemo-Basic")
    val sc = SupplyChainModel(m, name = "SC")
    val net = MultiEchelonNetwork(
        sc, name = "Net",
        transportStrategy = TransportStrategy.PerIHPTimeBased,
    )

    val item = net.addItemType("Widget", ConstantRV(1.0))
    val warehouse = net.addInventoryHoldingPoint("W")
    warehouse.addReorderPointReorderQuantityInventory(
        item, reorderPoint = 4, reorderQty = 20, initialOnHand = 20,
    )
    net.attachToExternalSupplier(warehouse, ConstantRV(3.0))

    val retailers = (1..3).map { i ->
        val r = net.addInventoryHoldingPoint("R$i")
        r.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 2, reorderQty = 5, initialOnHand = 10,
        )
        net.attachToSupplier(warehouse, r, ConstantRV(1.0))
        net.attachDemandGenerator(
            r, item,
            ExponentialRV(1.5, streamNum = 10 + i),
            name = "DG-R$i",
        )
        r
    }

    // The cost-formulation step — one line of construction, all the
    // rollup wiring happens inside.  After this, every cost-response
    // accessor on the network returns a populated Response.
    DefaultMultiEchelonCostFormulation(net)

    m.numberOfReplications = 10
    m.lengthOfReplication = 365.0
    m.lengthOfReplicationWarmUp = 30.0
    m.simulate()

    println("=== half-width summary report ===")
    m.simulationReporter.printHalfWidthSummaryReport()

    // Top-line cost accessors for quick programmatic inspection.
    println()
    println("=== top-line cost responses (last-replication values) ===")
    println("Grand total              : ${net.totalCostResponse!!.value}")
    println("IHP-tier total           : ${net.totalIHPCostResponse!!.value}")
    println("CD-tier total            : ${net.totalCrossDockCostResponse!!.value}")
    println("ES-tier total            : ${net.totalExternalSupplierLoadingCostResponse!!.value}")
    println("Backorder cost           : ${net.totalBackorderCostResponse!!.value}")
    println("Stockout cost            : ${net.totalStockoutCostResponse!!.value}")
    println("Lost-sale cost           : ${net.totalLostSaleCostResponse!!.value}")
    println("Unit-shortage cost       : ${net.totalUnitShortageCostResponse!!.value}")
}
