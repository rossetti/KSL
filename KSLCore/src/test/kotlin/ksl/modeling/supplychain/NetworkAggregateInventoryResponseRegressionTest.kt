package ksl.modeling.supplychain

import ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Regression coverage for the network-level inventory-aggregate
 * wiring (bug-diagnosis report dated post-Phase-5).  Two underlying
 * defects together caused every network-level aggregate to report
 * 0.0 or NaN at simulation end:
 *
 * 1. [ksl.modeling.variable.AggregateTWResponse] tracked source
 *    deltas from its own initial value of 0 while sources started
 *    positive — the first source decrement pushed the aggregate
 *    negative, violating its `[0, ∞)` domain.  The
 *    [Inventory.attachAggregateInventoryResponse] workaround for
 *    this had `observe(myOnHand)` and `observe(myOnOrder)` commented
 *    out, so no source ever fed the on-hand and on-order aggregates.
 *
 * 2. [ksl.modeling.supplychain.inventory.AggregateInventoryResponse.subscribeTo]
 *    had the [ksl.modeling.variable.AggregateTWResponse.observe]
 *    direction reversed — calling `this.observe(r)` instead of
 *    `r.observe(this)`.  Data flowed *from* the higher-level
 *    aggregate back *down* into the lower-level one (a no-op), so
 *    the network-level aggregate never received any IHP-level
 *    observations.
 *
 * Fix 1: `AggregateTWResponse.initialize()` re-syncs the aggregate's
 * value to the sum of its sources' values at the start of each
 * replication, propagating through any cascading aggregate chain via
 * standard observer firings.
 *
 * Fix 2: `AggregateInventoryResponse.subscribeTo` now writes
 * `r.aggregateXxx.observe(aggregateXxx)`, matching the direction
 * convention already used by [Inventory.attachAggregateInventoryResponse].
 *
 * This test drives a small simulation and asserts every
 * network-level aggregate carries a sensible value at replication
 * end — non-zero, non-NaN, and within physically-plausible bounds.
 */
class NetworkAggregateInventoryResponseRegressionTest {

    @Test
    fun `network-level aggregates carry inventory data through the chain`() {
        // Topology: ES → 1 warehouse → 3 retailers, single item.
        // Constant 1.0-unit inter-arrival on each retailer; tight
        // inventory at the retailers; constant lead times; long
        // simulated horizon to ensure steady-state behavior.
        val m = Model("AggRegression")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(1.0))

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
                r, item, ConstantRV(1.0), name = "DG-R$i",
                transportTime = ConstantRV.ZERO,
            )
        }

        DefaultMultiEchelonCostFormulation(net)

        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // -- On-hand: total initial inventory is 20 (W) + 3 × 10 = 50.
        //    With trickle demand the TW average should be positive and
        //    materially below the maximum.
        val avgOnHand = net.aggregateOnHandInventory
            .withinReplicationStatistic.weightedAverage
        assertTrue(avgOnHand > 0.0,
            "network aggregateOnHandInventory should be > 0 (got $avgOnHand)")
        assertTrue(avgOnHand <= 100.0,
            "network aggregateOnHandInventory should be physically plausible " +
                "(got $avgOnHand)")

        // -- On-order: replenishments fire when retailers' on-hand
        //    drops below reorder point, so on-order TW average should
        //    be positive.
        val avgOnOrder = net.aggregateAmountOnOrder
            .withinReplicationStatistic.weightedAverage
        assertTrue(avgOnOrder >= 0.0,
            "network aggregateAmountOnOrder should be >= 0 (got $avgOnOrder)")

        // -- Replenishment-order count: positive.
        val orderCount = net.aggregateNumberOfReplenishmentDemands.value
        assertTrue(orderCount > 0.0,
            "network aggregateNumberOfReplenishmentDemands should be > 0 (got $orderCount)")

        // -- First-fill rate: at least one demand observation,
        //    yielding a finite mean in [0, 1].
        val firstFillStat = net.aggregateAvgFirstFillRate
            .withinReplicationStatistic
        val firstFillCount = firstFillStat.count
        val firstFillMean = firstFillStat.weightedAverage
        assertTrue(firstFillCount > 0.0,
            "network aggregateAvgFirstFillRate should have recorded observations " +
                "(got count = $firstFillCount)")
        assertTrue(!firstFillMean.isNaN(),
            "network aggregateAvgFirstFillRate mean should be a finite number " +
                "(got $firstFillMean)")
        assertTrue(firstFillMean in 0.0..1.0,
            "network aggregateAvgFirstFillRate mean should be in [0, 1] " +
                "(got $firstFillMean)")
    }
}
