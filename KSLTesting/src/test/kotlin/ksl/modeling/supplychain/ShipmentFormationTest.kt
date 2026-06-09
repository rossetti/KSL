package ksl.modeling.supplychain

import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.transport.*
import ksl.modeling.supplychain.network.*

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the per-edge shipment-formation policy on
 * [MultiEchelonNetwork] (Phase 1.3). Verifies opt-in, carrier
 * selection, builder configuration, end-to-end load shipment, and
 * the error cases.
 */
class ShipmentFormationTest {

    @Test
    fun `addInventoryHoldingPoint with enableShipmentFormation installs a TimeBasedLoadCarrier`() {
        val sc = SupplyChainModel(Model("SF.LoadCarrier"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val ihp = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        assertTrue(ihp.demandCarrier is TimeBasedLoadCarrier,
            "expected a TimeBasedLoadCarrier under PerIHPTimeBased + " +
                "enableShipmentFormation")
        // reactToLoadBuildersFlag must be on so loads ship automatically
        assertTrue((ihp.demandCarrier as TimeBasedLoadCarrier).reactToLoadBuildersFlag)
    }

    @Test
    fun `enableShipmentFormation under SharedCarrier is rejected`() {
        val sc = SupplyChainModel(Model("SF.Shared"))
        val net = MultiEchelonNetwork(sc, name = "Net")  // default SharedCarrier
        assertThrows<IllegalStateException> {
            net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        }
    }

    @Test
    fun `enableShipmentFormation under NetworkTimeBased is rejected`() {
        val sc = SupplyChainModel(Model("SF.NetTB"))
        val sharedCarrier = TimeBasedNetworkDemandCarrier(sc, name = "NC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.NetworkTimeBased(sharedCarrier),
        )
        assertThrows<IllegalStateException> {
            net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        }
    }

    @Test
    fun `shipmentFormation on a non-formation supplier is rejected`() {
        val sc = SupplyChainModel(Model("SF.NotEnabled"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        // Note: no enableShipmentFormation = true here.
        net.addItemType("A", ConstantRV(1.0))
        val supplier = net.addInventoryHoldingPoint("P")
        val customer = net.addInventoryHoldingPoint("C")
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        assertThrows<IllegalStateException> {
            net.attachToSupplier(
                supplier, customer, ConstantRV(0.5),
                shipmentFormation = ShipmentFormation(
                    DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 3,
                ),
            )
        }
    }

    @Test
    fun `attachToSupplier with COUNT formation configures the builder correctly`() {
        val sc = SupplyChainModel(Model("SF.Configure"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val supplier = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        val customer = net.addInventoryHoldingPoint("C")
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        net.attachToSupplier(
            supplier, customer, ConstantRV(0.5),
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 3,
            ),
        )
        val loadCarrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val builder = loadCarrier.getLoadBuilder(customer)
        assertEquals(DemandLoadBuilder.LoadFormingOption.COUNT, builder.loadFormingOption)
        assertEquals(3, builder.countLimit)
    }

    @Test
    fun `customer without explicit formation gets a default ALWAYS builder`() {
        val sc = SupplyChainModel(Model("SF.Default"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val supplier = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        val customer = net.addInventoryHoldingPoint("C")
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        // No shipmentFormation passed.
        net.attachToSupplier(supplier, customer, ConstantRV(0.5))
        val loadCarrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val builder = loadCarrier.getLoadBuilder(customer)
        assertEquals(DemandLoadBuilder.LoadFormingOption.ALWAYS, builder.loadFormingOption)
    }

    @Test
    fun `end-to-end COUNT formation bundles demands before delivery`() {
        val m = Model("SF.EndToEnd")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("A", ConstantRV(0.25))
        val supplier = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        supplier.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 10, initialOnHand = 20,
        )
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        net.attachDemandGenerator(
            supplier, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 4,
            ),
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 12.0
        m.simulate()

        // Supplier shipped 12 demands (one per unit time); with
        // countLimit = 4 we expect 3 full loads to have been formed
        // and shipped.  Counted via the load carrier's aggregate
        // shipment counter (counts loads, not individual demands).
        val loadCarrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val totalLoadsShipped = loadCarrier.shipmentCounter.value
        assertEquals(3.0, totalLoadsShipped,
            "expected exactly 3 loads from 12 demands under COUNT=4")
    }

    @Test
    fun `attachToSupplier with WEIGHT formation configures the builder correctly`() {
        val sc = SupplyChainModel(Model("SF.Weight"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val supplier = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        val customer = net.addInventoryHoldingPoint("C")
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        net.attachToSupplier(
            supplier, customer, ConstantRV(0.5),
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.WEIGHT,
                weightLimits = 10.0 to 50.0,
            ),
        )
        val loadCarrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val builder = loadCarrier.getLoadBuilder(customer)
        assertEquals(DemandLoadBuilder.LoadFormingOption.WEIGHT, builder.loadFormingOption)
        assertEquals(10.0, builder.minWeightLimit)
        assertEquals(50.0, builder.maxWeightLimit)
    }

    @Test
    fun `attachToSupplier with CUBE formation configures the builder correctly`() {
        val sc = SupplyChainModel(Model("SF.Cube"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val supplier = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        val customer = net.addInventoryHoldingPoint("C")
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        net.attachToSupplier(
            supplier, customer, ConstantRV(0.5),
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.CUBE,
                cubeLimits = 2.0 to 8.0,
            ),
        )
        val loadCarrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val builder = loadCarrier.getLoadBuilder(customer)
        assertEquals(DemandLoadBuilder.LoadFormingOption.CUBE, builder.loadFormingOption)
        assertEquals(2.0, builder.minCubeLimit)
        assertEquals(8.0, builder.maxCubeLimit)
    }

    @Test
    fun `attachToSupplier with ALWAYS formation forms one load per demand`() {
        // Explicit ALWAYS test (the default-builder test covers the
        // implicit path; this covers an explicit ShipmentFormation
        // construction with the ALWAYS option).
        val sc = SupplyChainModel(Model("SF.Always"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        net.addItemType("A", ConstantRV(1.0))
        val supplier = net.addInventoryHoldingPoint("P", enableShipmentFormation = true)
        val customer = net.addInventoryHoldingPoint("C")
        net.attachToExternalSupplier(supplier, ConstantRV(0.25))
        net.attachToSupplier(
            supplier, customer, ConstantRV(0.5),
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.ALWAYS,
            ),
        )
        val loadCarrier = supplier.demandCarrier as TimeBasedLoadCarrier
        val builder = loadCarrier.getLoadBuilder(customer)
        assertEquals(DemandLoadBuilder.LoadFormingOption.ALWAYS, builder.loadFormingOption)
    }

    @Test
    fun `ShipmentFormation init-block validation rejects misconfigured options`() {
        // COUNT requires countLimit > 0.
        assertThrows<IllegalArgumentException> {
            ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT,
                countLimit = 0,
            )
        }
        // WEIGHT requires weightLimits to be set.
        assertThrows<IllegalArgumentException> {
            ShipmentFormation(DemandLoadBuilder.LoadFormingOption.WEIGHT)
        }
        // CUBE requires cubeLimits to be set.
        assertThrows<IllegalArgumentException> {
            ShipmentFormation(DemandLoadBuilder.LoadFormingOption.CUBE)
        }
    }

    // -- External-supplier outbound formation ----------------------------

    @Test
    fun `enableExternalSupplierShipmentFormation installs a TimeBasedLoadCarrier on the ES`() {
        val sc = SupplyChainModel(Model("SF.ESLoadCarrier"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
            enableExternalSupplierShipmentFormation = true,
        )
        val carrier = net.externalSupplier.demandCarrier
        assertTrue(carrier is TimeBasedLoadCarrier,
            "expected ES carrier to be a TimeBasedLoadCarrier under " +
                "enableExternalSupplierShipmentFormation = true")
        assertTrue((carrier as TimeBasedLoadCarrier).reactToLoadBuildersFlag)
    }

    @Test
    fun `enableExternalSupplierShipmentFormation under SharedCarrier is rejected`() {
        val sc = SupplyChainModel(Model("SF.ESShared"))
        assertThrows<IllegalStateException> {
            MultiEchelonNetwork(
                sc, name = "Net",
                enableExternalSupplierShipmentFormation = true,
            )
        }
    }

    @Test
    fun `enableExternalSupplierShipmentFormation under NetworkTimeBased is rejected`() {
        val sc = SupplyChainModel(Model("SF.ESNetTB"))
        val sharedCarrier = TimeBasedNetworkDemandCarrier(sc, name = "NC")
        assertThrows<IllegalStateException> {
            MultiEchelonNetwork(
                sc, name = "Net",
                transportStrategy = TransportStrategy.NetworkTimeBased(sharedCarrier),
                enableExternalSupplierShipmentFormation = true,
            )
        }
    }

    @Test
    fun `attachToExternalSupplier with formation on a non-formation ES is rejected`() {
        val sc = SupplyChainModel(Model("SF.ESNotEnabled"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
            // enableExternalSupplierShipmentFormation NOT set
        )
        net.addItemType("A", ConstantRV(1.0))
        val ihp = net.addInventoryHoldingPoint("P")
        assertThrows<IllegalStateException> {
            net.attachToExternalSupplier(
                ihp, transportTime = ConstantRV(0.25),
                shipmentFormation = ShipmentFormation(
                    DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 3,
                ),
            )
        }
    }

    @Test
    fun `attachToExternalSupplier with COUNT formation configures the builder correctly`() {
        val sc = SupplyChainModel(Model("SF.ESConfigure"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
            enableExternalSupplierShipmentFormation = true,
        )
        net.addItemType("A", ConstantRV(1.0))
        val ihp = net.addInventoryHoldingPoint("P")
        net.attachToExternalSupplier(
            ihp, transportTime = ConstantRV(0.25),
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 3,
            ),
        )
        val loadCarrier = net.externalSupplier.demandCarrier as TimeBasedLoadCarrier
        val builder = loadCarrier.getLoadBuilder(ihp)
        assertEquals(DemandLoadBuilder.LoadFormingOption.COUNT, builder.loadFormingOption)
        assertEquals(3, builder.countLimit)
    }

    @Test
    fun `attachToExternalSupplier without formation on a formation-enabled ES gets a default ALWAYS builder`() {
        val sc = SupplyChainModel(Model("SF.ESDefault"))
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
            enableExternalSupplierShipmentFormation = true,
        )
        net.addItemType("A", ConstantRV(1.0))
        val ihp = net.addInventoryHoldingPoint("P")
        // No shipmentFormation passed.
        net.attachToExternalSupplier(ihp, transportTime = ConstantRV(0.25))
        val loadCarrier = net.externalSupplier.demandCarrier as TimeBasedLoadCarrier
        val builder = loadCarrier.getLoadBuilder(ihp)
        assertEquals(DemandLoadBuilder.LoadFormingOption.ALWAYS, builder.loadFormingOption)
    }

    @Test
    fun `end-to-end COUNT formation on the ES bundles replenishment demands`() {
        val m = Model("SF.ESEndToEnd")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
            enableExternalSupplierShipmentFormation = true,
        )
        val item = net.addItemType("A", ConstantRV(0.25))
        // Tight (reorderPoint, reorderQty) so replenishments fire often.
        val ihp = net.addInventoryHoldingPoint("P")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 2, initialOnHand = 2,
        )
        net.attachToExternalSupplier(
            ihp, transportTime = ConstantRV(0.25),
            shipmentFormation = ShipmentFormation(
                DemandLoadBuilder.LoadFormingOption.COUNT, countLimit = 3,
            ),
        )
        // Trickle customer demand so the IHP keeps draining.
        net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // Replenishment demands flowing back from ES → IHP get bundled
        // 3-at-a-time. Counts loads, not individual demands.
        val loadCarrier = net.externalSupplier.demandCarrier as TimeBasedLoadCarrier
        val totalLoadsShipped = loadCarrier.shipmentCounter.value
        assertTrue(totalLoadsShipped >= 1.0,
            "expected the ES load carrier to have shipped at least one " +
                "bundled load (got $totalLoadsShipped)")
    }
}
