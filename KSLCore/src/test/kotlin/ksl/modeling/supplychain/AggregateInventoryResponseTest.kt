package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AggregateInventoryResponseTest {

    @Test
    fun `construction creates named child responses parented to the supplied parent`() {
        val m = Model("AIR.construction")
        val sc = SupplyChainModel(m)
        val air = AggregateInventoryResponse(sc, baseName = "Holder")
        // Each underlying response is a model element with a derived name.
        assertTrue(air.aggregateOnHandInventory.name.contains("Holder"))
        assertTrue(air.aggregateAmountOnOrder.name.contains("Holder"))
        assertTrue(air.aggregateAmountBackOrdered.name.contains("Holder"))
        assertTrue(air.aggregateNumberBackOrdered.name.contains("Holder"))
        assertTrue(air.aggregateAvgFirstFillRate.name.contains("Holder"))
        assertTrue(air.aggregateAvgCustomerWaitTime.name.contains("Holder"))
        assertTrue(air.aggregateNumberOfReplenishmentDemands.name.contains("Holder"))
    }

    @Test
    fun `default name comes from the parent`() {
        val m = Model("AIR.defaultName")
        val sc = SupplyChainModel(m, name = "MySC")
        val air = AggregateInventoryResponse(sc)
        // Underlying responses should reference the parent's name.
        assertTrue(air.aggregateOnHandInventory.name.contains("MySC"))
    }

    @Test
    fun `subscribeTo and unsubscribeFrom run without error`() {
        val m = Model("AIR.subscribe")
        val sc = SupplyChainModel(m)
        val downstream = AggregateInventoryResponse(sc, baseName = "Downstream")
        val upstream = AggregateInventoryResponse(sc, baseName = "Upstream")
        // Subscribing chains the four TW aggregates plus the counter.
        // We can't easily observe the chain from a unit test (it fires
        // during simulation events), but we verify the calls don't throw.
        downstream.subscribeTo(upstream)
        downstream.unsubscribeFrom(upstream)
        // Sanity: structure is intact after the round trip
        assertNotNull(downstream.aggregateOnHandInventory)
    }
}
