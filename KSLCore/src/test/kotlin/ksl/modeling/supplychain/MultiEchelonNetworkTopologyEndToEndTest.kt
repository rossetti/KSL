package ksl.modeling.supplychain

import ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.network.*
import ksl.modeling.supplychain.transport.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2 gap-fill 2: end-to-end coverage on the unified
 * [MultiEchelonNetwork] for the **ES → middle-IHP → leaf-IHP**
 * topology — the inventory-tier mirror of galchall's
 * `NetworkWithDistributionCenterTest`.
 *
 * Verifies that a two-hop replenishment cascade actually fires
 * (the leaf IHP draws stock, requests replenishment from the
 * middle IHP, the middle IHP in turn requests from the ES) and
 * that the cost responses populate correctly across both upstream
 * hops.
 */
class MultiEchelonIHPTopologyEndToEndTest {

    @Test
    fun `ES to middle-IHP to leaf-IHP topology replenishes across two hops`() {
        val m = Model("MEIHP.TwoHop")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ConstantRV(0.25))

        // Middle IHP holds bulk stock and serves the leaf.
        val middle = net.addInventoryHoldingPoint("Middle")
        val middleInv = middle.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 2, reorderQty = 10, initialOnHand = 10,
        )
        net.attachToExternalSupplier(middle, ConstantRV(0.25))

        // Leaf IHP serves the customer.
        val leaf = net.addInventoryHoldingPoint("Leaf")
        val leafInv = leaf.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToSupplier(middle, leaf, ConstantRV(0.5))

        // Trickle customer demand at the leaf.
        net.attachDemandGenerator(
            leaf, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        DefaultMultiEchelonCostFormulation(net)

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // Topology assertions.
        assertEquals(2, net.getInventoryHoldingPoints().size)
        assertEquals(0, net.getInventoryCrossDocks().size)
        assertEquals(1, middle.level)
        assertEquals(2, leaf.level)

        // Replenishment cascade fired at both tiers: leaf
        // requested from middle, middle requested from ES.
        assertTrue(leafInv.orderCounterWithinReplication > 0.0,
            "leaf IHP should have triggered at least one replenishment " +
                "(got ${leafInv.orderCounterWithinReplication})")
        assertTrue(middleInv.orderCounterWithinReplication > 0.0,
            "middle IHP should have triggered at least one replenishment " +
                "to the ES (got ${middleInv.orderCounterWithinReplication})")

        // Cost responses populate on both tiers.
        assertTrue(net.totalIHPCostResponse!!.value > 0.0,
            "IHP-tier cost should be > 0 (got ${net.totalIHPCostResponse!!.value})")
        assertTrue(net.totalExternalSupplierLoadingCostResponse!!.value > 0.0,
            "ES loading cost should be > 0 " +
                "(got ${net.totalExternalSupplierLoadingCostResponse!!.value})")
        // No cross-docks in this topology.
        assertEquals(0.0, net.totalCrossDockCostResponse!!.value, 1e-9)
    }

    @Test
    fun `customer demand is fulfilled at the leaf inventory`() {
        // Sanity: the customer-side fill-rate response shows actual
        // demand satisfaction during the run.
        val m = Model("MEIHP.LeafFill")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ConstantRV(0.25))
        val middle = net.addInventoryHoldingPoint("Middle")
        middle.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 2, reorderQty = 10, initialOnHand = 10,
        )
        net.attachToExternalSupplier(middle, ConstantRV(0.25))
        val leaf = net.addInventoryHoldingPoint("Leaf")
        val leafInv = leaf.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToSupplier(middle, leaf, ConstantRV(0.5))
        net.attachDemandGenerator(
            leaf, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // First-fill-rate observations recorded at the leaf inventory
        // (one per customer demand the leaf saw).
        val fillObservations = leafInv.firstFillRateWithinReplication.count
        assertTrue(fillObservations >= 25.0,
            "expected ≥ 25 customer demands fulfilled at the leaf over " +
                "30 time units (got $fillObservations)")
    }
}
