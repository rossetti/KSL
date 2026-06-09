package ksl.examples.general.supplychain

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.TimeBasedNetworkDemandCarrier
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Multi-echelon network using a SHARED [TimeBasedNetworkDemandCarrier]
 * for transport between every supplier-customer pair, rather than a
 * per-IHP carrier. Demonstrates the
 * [TimeBasedNetworkShippingMultiEchelonNetwork] facade.
 *
 * Mirrors the Java reference
 * `sc.mimenetworks.TestMultiEchelonInventoryNetworkWithNetworkShipper`.
 */
fun main() {
    val m = Model("MultiEchelon-NetworkShipper")
    val sc = SupplyChainModel(m, name = "ME-NetworkShipper-SC")
    buildModel(sc)

    m.numberOfReplications = 10
    m.lengthOfReplication = 3650.0
    m.lengthOfReplicationWarmUp = 100.0
    m.simulate()
    m.simulationReporter.printHalfWidthSummaryReport()
}

private fun buildModel(sc: SupplyChainModel) {
    val carrier = TimeBasedNetworkDemandCarrier(sc, name = "Network-Carrier")
    val net = MultiEchelonNetwork(
        sc, name = "ME-Inventory-Network",
        transportStrategy = TransportStrategy.NetworkTimeBased(carrier),
    )

    val leadTime = ConstantRV.ONE
    val type1 = net.addItemType("Type-1", leadTime)
    val type2 = net.addItemType("Type-2", leadTime)
    val type3 = net.addItemType("Type-3", leadTime)

    val warehouse = net.addInventoryHoldingPoint("Warehouse")
    warehouse.addReorderPointReorderQuantityInventory(type1, 4, 1, 20)
    warehouse.addReorderPointReorderQuantityInventory(type2, 4, 1, 20)
    warehouse.addReorderPointReorderQuantityInventory(type3, 4, 1, 20)

    val r1 = net.addInventoryHoldingPoint("R1")
    val r2 = net.addInventoryHoldingPoint("R2")
    val r3 = net.addInventoryHoldingPoint("R3")
    val r4 = net.addInventoryHoldingPoint("R4")

    r1.addReorderPointReorderQuantityInventory(type1, 2, 1, 10)
    r1.addReorderPointReorderQuantityInventory(type2, 2, 1, 10)

    r2.addReorderPointReorderQuantityInventory(type1, 2, 1, 10)
    r2.addReorderPointReorderQuantityInventory(type2, 2, 1, 10)
    r2.addReorderPointReorderQuantityInventory(type3, 2, 1, 10)

    r3.addReorderPointReorderQuantityInventory(type1, 2, 1, 10)
    r3.addReorderPointReorderQuantityInventory(type2, 2, 1, 10)
    r3.addReorderPointReorderQuantityInventory(type3, 2, 1, 10)

    r4.addReorderPointReorderQuantityInventory(type1, 2, 1, 10)
    r4.addReorderPointReorderQuantityInventory(type2, 2, 1, 10)

    // Topology — every leg uses the shared network carrier.
    net.attachIHPToExternalSupplier(warehouse)
    net.attachIHPToSupplier(warehouse, r1, leadTime)
    net.attachIHPToSupplier(warehouse, r2, leadTime)
    net.attachIHPToSupplier(warehouse, r3, leadTime)
    net.attachIHPToSupplier(warehouse, r4, leadTime)

    // Customer demand.
    var streamNum = 20
    val rate = { ExponentialRV(1.0, streamNum = streamNum++) }
    net.attachDemandGeneratorToIHP(r1, type1, rate())
    net.attachDemandGeneratorToIHP(r1, type2, rate())
    net.attachDemandGeneratorToIHP(r2, type1, rate())
    net.attachDemandGeneratorToIHP(r2, type2, rate())
    net.attachDemandGeneratorToIHP(r2, type3, rate())
    net.attachDemandGeneratorToIHP(r3, type1, rate())
    net.attachDemandGeneratorToIHP(r3, type2, rate())
    net.attachDemandGeneratorToIHP(r3, type3, rate())
    net.attachDemandGeneratorToIHP(r4, type1, rate())
    net.attachDemandGeneratorToIHP(r4, type2, rate())
}
