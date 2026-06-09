package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Integration tests for [InventoryCrossDock] embedded in a
 * [MultiEchelonNetwork]. Verifies that the network's topology
 * tracking, level assignment, and per-strategy carrier wiring all
 * work uniformly for cross-docks alongside IHPs.
 */
class MultiEchelonCrossDockIntegrationTest {

    @Test
    fun `cross-dock can be attached to the external supplier under SharedCarrier`() {
        val sc = SupplyChainModel(Model("MECD.Shared"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        net.addItemType("A", ConstantRV(1.0))
        val cd = net.addInventoryCrossDock("CD")
        net.attachToExternalSupplier(cd)
        assertTrue(net.isAttachedToExternalSupplier(cd))
        assertEquals(1, cd.level)
        assertSame(net.externalSupplier, cd.demandFiller)
        // Cross-dock uses the network's forwarding adapter just like
        // an IHP would — same carrier instance.
        val ihp = net.addInventoryHoldingPoint("P")
        net.attachToExternalSupplier(ihp)
        assertSame(cd.demandCarrier, ihp.demandCarrier)
    }

    @Test
    fun `3-tier topology ES to CD to IHP attaches and tracks levels`() {
        val sc = SupplyChainModel(Model("MECD.3Tier"))
        val net = MultiEchelonNetwork(sc, name = "Net")
        net.addItemType("A", ConstantRV(1.0))
        val cd = net.addInventoryCrossDock("CD")
        val ihp = net.addInventoryHoldingPoint("R")
        net.attachToExternalSupplier(cd)
        net.attachToSupplier(cd, ihp)
        assertEquals(1, cd.level)
        assertEquals(2, ihp.level)
        assertTrue(net.isCustomer(cd, ihp))
        assertSame(cd, ihp.demandFiller)
        // Per-type accessors only return their own kind.
        assertEquals(listOf(cd), net.getInventoryCrossDocks(level = 1))
        assertEquals(listOf(ihp), net.getInventoryHoldingPoints(level = 2))
        // Generalised accessor returns both.
        assertEquals(2, net.getNodes().size)
    }

    @Test
    fun `cross-dock chain ES to CD to CD to IHP under NetworkTimeBased`() {
        val sc = SupplyChainModel(Model("MECD.Chain"))
        val carrier = TimeBasedNetworkDemandCarrier(sc, name = "Net.Carrier")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.NetworkTimeBased(carrier),
        )
        net.addItemType("A", ConstantRV(1.0))
        val cd1 = net.addInventoryCrossDock("CD1")
        val cd2 = net.addInventoryCrossDock("CD2")
        val ihp = net.addInventoryHoldingPoint("R")
        net.attachToExternalSupplier(cd1, ConstantRV(0.25))
        net.attachToSupplier(cd1, cd2, ConstantRV(0.25))
        net.attachToSupplier(cd2, ihp, ConstantRV(0.25))
        assertEquals(1, cd1.level)
        assertEquals(2, cd2.level)
        assertEquals(3, ihp.level)
        // All nodes share the network carrier under NetworkTimeBased.
        assertSame(carrier, cd1.demandCarrier)
        assertSame(carrier, cd2.demandCarrier)
        assertSame(carrier, ihp.demandCarrier)
    }

    @Test
    fun `PerIHPTimeBased gives the cross-dock its own TimeBasedDemandCarrier`() {
        val sc = SupplyChainModel(Model("MECD.PerIHP"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val cd = net.addInventoryCrossDock("CD")
        val ihp = net.addInventoryHoldingPoint("P")
        net.attachToExternalSupplier(cd, ConstantRV(0.5))
        net.attachToSupplier(cd, ihp, ConstantRV(0.5))
        assertTrue(cd.demandCarrier is TimeBasedDemandCarrier)
        assertTrue(ihp.demandCarrier is TimeBasedDemandCarrier)
        // Each node has its OWN carrier.
        assertTrue(cd.demandCarrier !== ihp.demandCarrier)
    }

    @Test
    fun `end-to-end demand flow ES to CD to IHP fills the customer demand`() {
        val m = Model("MECD.EndToEnd")
        val sc = SupplyChainModel(m, name = "MECD-SC")
        val net = MultiEchelonNetwork(sc, name = "Net")
        val item = net.addItemType("A", ConstantRV(0.5))
        // Topology: ES → CD → IHP (with inventory).
        val cd = net.addInventoryCrossDock("CD")
        val ihp = net.addInventoryHoldingPoint("R")
        val inv = ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(cd)
        net.attachToSupplier(cd, ihp)
        net.attachDemandGenerator(ihp, item, ConstantRV(1.0), name = "DG")

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // Customer demands flowed and the IHP's inventory triggered
        // replenishments via the cross-dock.
        val customerFills = inv.firstFillRateWithinReplication.count
        val replenishmentsRequested = inv.orderCounterWithinReplication
        val cdRoundTrips = cd.numberOfDemandsCrossDockedResponse.value
        assertTrue(customerFills > 0.0,
            "customer demands never reached the IHP (count=$customerFills)",
        )
        assertTrue(replenishmentsRequested > 0.0,
            "IHP never triggered a replenishment (count=$replenishmentsRequested)",
        )
        assertTrue(cdRoundTrips > 0.0,
            "cross-dock saw $cdRoundTrips round trips; expected at least one " +
                "(customerFills=$customerFills, replenishments=$replenishmentsRequested)",
        )
    }
}
