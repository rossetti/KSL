package ksl.examples.general.supplychain

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.InventoryHoldingPoint
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Multi-echelon network with (s, S) — reorder-point / order-up-to-level
 * — inventory at five retailers, served by a single warehouse, which
 * itself is replenished by an external supplier on a 3.0-time-unit
 * transport leg.
 *
 * Mirrors the Java reference
 * `sc.mimenetworks.TestMultiEchelonInventoryNetworkExampleSS`.
 *
 * Run via `gradle :KSLExamples:run -PmainClass=ksl.examples.general.supplychain.MultiEchelonNetworkSSPolicyExampleKt`
 * or by invoking the `main` function from an IDE.
 */
fun main() {
    val m = Model("MultiEchelon-SS-Policy")
    val sc = SupplyChainModel(m, name = "ME-Inventory-Network-SC")
    buildModel(sc)

    m.numberOfReplications = 30
    m.lengthOfReplication = 5400.0
    m.lengthOfReplicationWarmUp = 1800.0
    m.simulate()
    m.simulationReporter.printHalfWidthSummaryReport()
}

private fun buildModel(sc: SupplyChainModel) {
    val net = MultiEchelonNetwork(
        sc, name = "ME-Inventory-Network",
        transportStrategy = TransportStrategy.PerIHPTimeBased,
    )

    // Item types with per-item production lead times at the supplier.
    // Stream numbers are explicit per CLAUDE.md §4.1 (reproducibility).
    val type1 = net.addItemType("Type-1", ExponentialRV(1.0, streamNum = 1))
    val type2 = net.addItemType("Type-2", ExponentialRV(0.5, streamNum = 2))
    val type3 = net.addItemType("Type-3", ExponentialRV(1.5, streamNum = 3))
    val type4 = net.addItemType("Type-4", ExponentialRV(2.0, streamNum = 4))

    // Warehouse with (R, Q) reorder-point/reorder-quantity inventory.
    val warehouse: InventoryHoldingPoint = net.addInventoryHoldingPoint("Warehouse")
    warehouse.addReorderPointReorderQuantityInventory(type1, 4, 1, 20)
    warehouse.addReorderPointReorderQuantityInventory(type2, 5, 1, 20)
    warehouse.addReorderPointReorderQuantityInventory(type3, 3, 2, 20)
    warehouse.addReorderPointReorderQuantityInventory(type4, 4, 2, 20)

    // Five retailers with (R, S) reorder-point/order-up-to-level inventory.
    val retailers = (1..5).map { i ->
        net.addInventoryHoldingPoint("R$i")
    }
    val rsTable = listOf(
        listOf(2 to 3, 1 to 2, 2 to 4, 3 to 6), // R1
        listOf(1 to 3, 2 to 4, 2 to 5, 2 to 3), // R2
        listOf(2 to 4, 1 to 2, 2 to 3, 2 to 3), // R3
        listOf(3 to 6, 3 to 4, 1 to 2, 2 to 3), // R4
        listOf(2 to 3, 0 to 1, 3 to 6, 1 to 2), // R5
    )
    val itemTypes = listOf(type1, type2, type3, type4)
    for ((retailer, row) in retailers.zip(rsTable)) {
        for ((type, rs) in itemTypes.zip(row)) {
            retailer.addReorderPointOrderUpToLevelInventory(type, rs.first, rs.second, 10)
        }
    }

    // Network topology.
    net.attachIHPToExternalSupplier(warehouse, ConstantRV(3.0))
    val customerLeg = ConstantRV(1.0)
    for (retailer in retailers) {
        net.attachIHPToSupplier(warehouse, retailer, customerLeg)
    }

    // Inter-arrival demand rates per (retailer, item type) — 20 distinct
    // ExponentialRV streams. Means taken from the Java reference.
    val demandMeans = listOf(
        listOf(2.0, 1.0, 1.5, 3.0), // R1
        listOf(1.0, 2.0, 2.5, 1.5), // R2
        listOf(2.5, 1.5, 2.0, 2.0), // R3
        listOf(3.0, 2.5, 1.0, 2.5), // R4
        listOf(1.5, 0.5, 3.0, 0.5), // R5
    )
    var streamNum = 10
    for ((retailer, means) in retailers.zip(demandMeans)) {
        for ((type, mean) in itemTypes.zip(means)) {
            net.attachDemandGeneratorToIHP(
                retailer, type,
                ExponentialRV(mean, streamNum = streamNum++),
            )
        }
    }
}
