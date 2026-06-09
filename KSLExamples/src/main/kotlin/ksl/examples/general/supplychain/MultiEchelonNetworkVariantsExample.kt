package ksl.examples.general.supplychain

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.InventoryHoldingPoint
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.transport.NoDelayDemandCarrier
import ksl.modeling.supplychain.transport.TransportDelay
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Compares two transport configurations on the same basic
 * [MultiEchelonNetwork] topology (1 warehouse, 2 retailers, 2 item
 * types):
 *
 *   - **buildTest1**: no carrier — every transport is instantaneous
 *     (the network defaults to [NoDelayDemandCarrier]).
 *   - **buildTest2**: a [TransportDelay] carrier with a 0.5-time-unit
 *     transport time, applied uniformly across every leg.
 *
 * Mirrors the four `test*`/`buildTest*` scenarios in the Java reference
 * `sc.mimenetworks.TestNetworkModels` (Test 1 and Test 2 are the most
 * representative; the variants in the original Java file are minor
 * tweaks of these two).
 */
fun main() {
    runVariant("variant1-no-delay", buildVariant1())
    runVariant("variant2-uniform-delay", buildVariant2())
}

private fun runVariant(label: String, model: Model) {
    model.numberOfReplications = 100
    model.lengthOfReplication = 5400.0
    model.lengthOfReplicationWarmUp = 1800.0
    println("=== Running $label ===")
    model.simulate()
    model.simulationReporter.printHalfWidthSummaryReport()
    println()
}

/** Variant 1 — no delay carrier (instantaneous transport). */
private fun buildVariant1(): Model {
    val m = Model("ME-Variant-1-NoDelay")
    val sc = SupplyChainModel(m, name = "Variant1-SC")
    val net = MultiEchelonNetwork(sc, name = "Network")
    // Carrier left at default (NoDelayDemandCarrier).
    populateBasicTopology(net, demandStreamSeed = 60)
    return m
}

/** Variant 2 — uniform transport delay across all legs. */
private fun buildVariant2(): Model {
    val m = Model("ME-Variant-2-UniformDelay")
    val sc = SupplyChainModel(m, name = "Variant2-SC")
    val net = MultiEchelonNetwork(sc, name = "Network")
    net.demandCarrier = TransportDelay(
        net, transportTime = ConstantRV(0.5), name = "UniformDelay",
    ).let { delay ->
        // TransportDelay is a model-element-backed carrier; wrap it as
        // a DemandCarrierIfc by exposing transportDemand via a small
        // adapter. (The carrier slot accepts DemandCarrierIfc.)
        object : ksl.modeling.supplychain.DemandCarrierIfc {
            override fun transportDemand(demand: SupplyChainModel.Demand) =
                delay.startDelay(demand)
            override fun canShip(demand: SupplyChainModel.Demand): Boolean = true
        }
    }
    populateBasicTopology(net, demandStreamSeed = 70)
    return m
}

private fun populateBasicTopology(
    net: MultiEchelonNetwork,
    demandStreamSeed: Int,
) {
    val lt = ConstantRV(0.5)
    val typeA = net.addItemType("TypeA", lt)
    val typeB = net.addItemType("TypeB", lt)

    val w: InventoryHoldingPoint = net.addInventoryHoldingPoint("W")
    val r1 = net.addInventoryHoldingPoint("R1")
    val r2 = net.addInventoryHoldingPoint("R2")

    net.attachIHPToExternalSupplier(w)
    net.attachIHPToSupplier(w, r1)
    net.attachIHPToSupplier(w, r2)

    w.addReorderPointReorderQuantityInventory(typeA, 3, 2)
    w.addReorderPointReorderQuantityInventory(typeB, 2, 2)
    r1.addReorderPointReorderQuantityInventory(typeA, 3, 2)
    r1.addReorderPointReorderQuantityInventory(typeB, 2, 2)
    r2.addReorderPointReorderQuantityInventory(typeA, 3, 2)
    r2.addReorderPointReorderQuantityInventory(typeB, 2, 2)

    var streamNum = demandStreamSeed
    val tbd = { ExponentialRV(1.0 / 3.6, streamNum = streamNum++) }
    net.attachDemandGeneratorToIHP(r1, typeA, tbd())
    net.attachDemandGeneratorToIHP(r1, typeB, tbd())
    net.attachDemandGeneratorToIHP(r2, typeA, tbd())
    net.attachDemandGeneratorToIHP(r2, typeB, tbd())
}
