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
 * Cost-formulation demo 4 — comparative analysis with multiple
 * formulations attached to the same network.
 *
 * Two [DefaultMultiEchelonCostFormulation]s with different
 * [CostParams] are attached to one network and produce independent
 * cost responses in the same simulation run.  This is the
 * "comparative-study on one simulation" pattern — useful for asking
 * "how do the rollups change under a different cost regime?"
 * without re-running the simulation.
 *
 * Each formulation is named at construction so its Responses appear
 * under distinct prefixes in the half-width report
 * (`StandardCostModel:Total:Tier:IHP` vs.
 * `HighCarryingCostModel:Total:Tier:IHP`).
 *
 * The two formulations are independent — different rates, different
 * Responses, no shared state.  Both compute against the same
 * underlying observables, so a 2× increase in carrying rate produces
 * a 2× increase in the holding line item.
 */
fun main() {
    val m = Model("CostDemo-Comparative")
    val sc = SupplyChainModel(m, name = "SC")
    val net = MultiEchelonNetwork(
        sc, name = "Net",
        transportStrategy = TransportStrategy.PerIHPTimeBased,
    )

    val item = net.addItemType("Widget", ConstantRV(1.0)).apply { unitCost = 12.50 }
    val warehouse = net.addInventoryHoldingPoint("W")
    warehouse.addReorderPointReorderQuantityInventory(
        item, reorderPoint = 4, reorderQty = 20, initialOnHand = 20,
    )
    net.attachToExternalSupplier(warehouse, ConstantRV(3.0))

    (1..3).forEach { i ->
        val r = net.addInventoryHoldingPoint("R$i")
        r.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 2, reorderQty = 5, initialOnHand = 10,
        )
        net.attachToSupplier(warehouse, r, ConstantRV(1.0))
        net.attachDemandGenerator(
            r, item, ExponentialRV(1.5, streamNum = 10 + i),
            name = "DG-R$i",
        )
    }

    // Two formulations.  Both attach to the same network; their
    // calculators observe the same sources and run independently.
    val standardParams = CostParams(carryingRate = 0.10, orderingCost = 50.0)
    val highCarryingParams = CostParams(carryingRate = 0.20, orderingCost = 50.0)

    val standardFormulation = DefaultMultiEchelonCostFormulation(
        net, standardParams, name = "StandardCostModel",
    )
    val highCarryingFormulation = DefaultMultiEchelonCostFormulation(
        net, highCarryingParams, name = "HighCarryingCostModel",
    )

    m.numberOfReplications = 10
    m.lengthOfReplication = 365.0
    m.lengthOfReplicationWarmUp = 30.0
    m.simulate()

    println("=== half-width summary report (both formulations side-by-side) ===")
    m.simulationReporter.printHalfWidthSummaryReport()

    // Programmatic check: doubling carrying rate doubles holding cost.
    println()
    println("=== sanity check: 2x carrying rate ⇒ 2x holding cost ===")
    val standardHolding = standardFormulation
        .byLineResponse(ksl.modeling.supplychain.cost.CostLine.Holding)!!.value
    val highHolding = highCarryingFormulation
        .byLineResponse(ksl.modeling.supplychain.cost.CostLine.Holding)!!.value
    println("Standard holding (rate 0.10) : %.4f".format(standardHolding))
    println("High holding     (rate 0.20) : %.4f".format(highHolding))
    println("Ratio                          : %.4f (expected 2.0)".format(highHolding / standardHolding))

    println()
    println("=== grand totals ===")
    println("Standard total : %.2f".format(standardFormulation.totalCostResponse.value))
    println("High total     : %.2f".format(highCarryingFormulation.totalCostResponse.value))

    // Note: net.totalCostResponse reads from the *first* attached
    // formulation (here `standardFormulation`).  If you want a
    // specific formulation, read its property directly.
}
