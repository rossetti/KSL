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
 * Integration tests for cost-formulation wiring on
 * [MultiEchelonNetwork].  Runs short simulations and asserts that
 * the per-tier cost-response accessors expose the values produced by
 * an attached [DefaultMultiEchelonCostFormulation].  Phase 4 of the
 * cost redesign moved cost computation from the legacy
 * `MultiEchelonCostModel` slot composition to per-source observer
 * calculators owned by the formulation; the network's existing
 * `total*Response` properties now read from the formulation's
 * rollups.
 */
class MultiEchelonNetworkCostTest {

    @Test
    fun `replicationEnded leaves zero costs on the no-shipment SharedCarrier path`() {
        // SharedCarrier(NoDelayDemandCarrier) has no per-edge tracking,
        // so flow-line responses report 0; only inventory-side lines
        // (Holding, Ordering, etc.) carry value.
        val m = Model("MECost.Shared")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(sc, name = "Net")
        val item = net.addItemType("A", ConstantRV(0.25))
        val ihp = net.addInventoryHoldingPoint("P")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(ihp)
        net.attachDemandGenerator(ihp, item, ConstantRV(1.0), name = "DG")

        DefaultMultiEchelonCostFormulation(net)

        m.numberOfReplications = 1
        m.lengthOfReplication = 12.0
        m.simulate()

        assertTrue(net.totalCostResponse!!.value >= 0.0)
        // ES tier rollup is 0 — no ESCostCalculator built under
        // SharedCarrier because no per-edge counter exists.
        assertEquals(0.0,
            net.totalExternalSupplierLoadingCostResponse!!.value, 1e-9)
    }

    @Test
    fun `PerIHPTimeBased populates all per-tier responses with non-zero values`() {
        val m = Model("MECost.PerIHP")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ConstantRV(0.25))
        val ihp = net.addInventoryHoldingPoint("P")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(ihp, ConstantRV(0.25))
        net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        DefaultMultiEchelonCostFormulation(net)

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // Replenishments triggered → ES shipped → IHP received → IHP
        // shipped to generator. All tiers should show activity.
        assertTrue(net.totalExternalSupplierLoadingCostResponse!!.value > 0.0,
            "ES loading cost should be > 0 (got " +
                "${net.totalExternalSupplierLoadingCostResponse!!.value})")
        assertTrue(net.totalIHPCostResponse!!.value > 0.0,
            "IHP tier cost should be > 0 (got " +
                "${net.totalIHPCostResponse!!.value})")
        // No cross-docks in this topology.
        assertEquals(0.0, net.totalCrossDockCostResponse!!.value, 1e-9)
        // Top-line total = IHP + CD + ES; CD is zero so total = IHP + ES.
        val expected = net.totalIHPCostResponse!!.value +
            net.totalCrossDockCostResponse!!.value +
            net.totalExternalSupplierLoadingCostResponse!!.value
        assertEquals(expected, net.totalCostResponse!!.value, 1e-9)
    }

    @Test
    fun `shipmentBuildingHoldingCost is non-zero when formation buffers demands`() {
        // Design-doc §10 item #3. With COUNT formation > 1 the
        // builder holds demands across the inter-arrival gap, so the
        // per-item TWResponse has a positive time-weighted average
        // and the ShipmentBuilderHolding line is > 0 — which
        // contributes to the IHP-tier rollup.
        val m = Model("Cost.SBHoldingCost")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ConstantRV(0.25))
        val supplier = net.addInventoryHoldingPoint(
            "P", enableShipmentFormation = true,
        )
        supplier.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 2, reorderQty = 20, initialOnHand = 50,
        )
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        net.attachDemandGenerator(
            supplier, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 5,
            ),
        )

        DefaultMultiEchelonCostFormulation(net)

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        assertTrue(net.totalIHPCostResponse!!.value > 0.0)
    }
}
