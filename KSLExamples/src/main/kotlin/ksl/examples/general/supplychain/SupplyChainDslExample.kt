package ksl.examples.general.supplychain

import ksl.modeling.supplychain.spec.CostParamsSpec
import ksl.modeling.supplychain.spec.PolicySpec
import ksl.modeling.supplychain.spec.RVSpec
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.constant
import ksl.modeling.supplychain.spec.exponential
import ksl.modeling.supplychain.spec.supplyChain
import ksl.simulation.Model

/**
 * Authors the (s, S) warehouse-serves-five-retailers network with the
 * Kotlin DSL (DSL plan Phase D4) and runs it.  The DSL produces a
 * `NetworkSpec` — the same data the TOML/JSON loaders accept — which
 * one [SupplyChainBuilder.build] call turns into a running model.
 *
 * The five retailers and their per-(retailer, item) (s, S) policies and
 * demand rates are expanded from `rsTable` / `demandMeans` via
 * `tierFromTables`, mirroring `MultiEchelonNetworkSSPolicyExample` but
 * authored as data.
 *
 * Run via
 * `gradle :KSLExamples:run -PmainClass=ksl.examples.general.supplychain.SupplyChainDslExampleKt`
 * or by invoking `main` from an IDE.
 */
fun main() {
    val rsTable = listOf(
        listOf(PolicySpec.SS(2, 3), PolicySpec.SS(1, 2), PolicySpec.SS(2, 4), PolicySpec.SS(3, 6)),
        listOf(PolicySpec.SS(1, 3), PolicySpec.SS(2, 4), PolicySpec.SS(2, 5), PolicySpec.SS(2, 3)),
        listOf(PolicySpec.SS(2, 4), PolicySpec.SS(1, 2), PolicySpec.SS(2, 3), PolicySpec.SS(2, 3)),
        listOf(PolicySpec.SS(3, 6), PolicySpec.SS(3, 4), PolicySpec.SS(1, 2), PolicySpec.SS(2, 3)),
        listOf(PolicySpec.SS(2, 3), PolicySpec.SS(0, 1), PolicySpec.SS(3, 6), PolicySpec.SS(1, 2)),
    )
    val demandMeans = listOf(
        listOf(2.0, 1.0, 1.5, 3.0),
        listOf(1.0, 2.0, 2.5, 1.5),
        listOf(2.5, 1.5, 2.0, 2.0),
        listOf(3.0, 2.5, 1.0, 2.5),
        listOf(1.5, 0.5, 3.0, 0.5),
    )

    val spec = supplyChain("ME-Inventory-Network", autoStreamBase = 10) {
        transportStrategy = perIHPTimeBased
        val type1 = item("Type-1", exponential(1.0, stream = 1))
        val type2 = item("Type-2", exponential(0.5, stream = 2))
        val type3 = item("Type-3", exponential(1.5, stream = 3))
        val type4 = item("Type-4", exponential(2.0, stream = 4))
        val items = listOf(type1, type2, type3, type4)

        holdingPoint("Warehouse") {
            attachedToExternalSupplier(constant(3.0))
            inventory(type1) { sQ(s = 4, Q = 1, initialOnHand = 20) }
            inventory(type2) { sQ(s = 5, Q = 1, initialOnHand = 20) }
            inventory(type3) { sQ(s = 3, Q = 2, initialOnHand = 20) }
            inventory(type4) { sQ(s = 4, Q = 2, initialOnHand = 20) }

            // Demand-rate table → per-(retailer, item) inter-arrival RVs,
            // each on its own auto-allocated stream (base 10).
            val demandTable = demandMeans.map { row ->
                row.map { mean -> exponential(mean, stream = autoStream()) as RVSpec? }
            }
            tierFromTables(
                namePrefix = "R",
                items = items,
                policyTable = rsTable,
                initialOnHand = 10,
                transportTime = constant(1.0),
                demandTable = demandTable,
            )
        }
        defaultCost(params = CostParamsSpec(carryingRate = 0.10))
    }

    val m = Model("SupplyChain-DSL")
    SupplyChainBuilder.build(m, spec)

    m.numberOfReplications = 30
    m.lengthOfReplication = 5400.0
    m.lengthOfReplicationWarmUp = 1800.0
    m.simulate()
    m.simulationReporter.printHalfWidthSummaryReport()
}
