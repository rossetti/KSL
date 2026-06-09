package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.supplychain.transport.TimeBasedDemandCarrier
import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase-1 (cost redesign) — confirms that
 * [TimeBasedDemandCarrier]'s new per-destination weight and cube
 * accumulators sum correctly across demands shipped on the edge.
 *
 * Setup: ES → IHP with a per-IHP time-based carrier, ConstantRV
 * lead times.  Single item type with `weight = 2.5` and `cube = 1.5`.
 * IHP runs a tight (s, Q) policy that fires `N` replenishment
 * orders during the simulated horizon; observer captures the
 * carrier's per-(ES, IHP) weight/cube accumulators at
 * REPLICATION_ENDED.
 *
 * Expected: each shipment carries `Q × weight` units of weight and
 * `Q × cube` units of cube.  Total weight = `#shipments × Q × weight`,
 * total cube = `#shipments × Q × cube`.
 */
class EdgeWeightCubeCounterTest {

    @Test
    fun `per-edge weight and cube accumulators sum demand weights and cubes`() {
        val m = Model("EdgeWC")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        // Item type with non-unit weight and cube so the assertions
        // are unambiguous.
        val item = net.addItemType(
            name = "X", leadTime = ConstantRV(0.25),
            weight = 2.5, cube = 1.5,
        )

        val ihp = net.addInventoryHoldingPoint("IHP")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        // Per-IHP time-based: the ES owns its own carrier.  Register
        // a non-zero transport time so the shipment counter and
        // weight/cube counters fire.
        net.attachToExternalSupplier(ihp, ConstantRV(0.5))
        // Customer demand drains the IHP, forcing replenishments.
        net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )

        // Capture state at REPLICATION_ENDED via an observer attached
        // to the ES carrier.  Read its per-destination accumulators.
        val esCarrier = net.externalSupplier.demandCarrier as TimeBasedDemandCarrier
        var capturedShipments = -1.0
        var capturedWeight = -1.0
        var capturedCube = -1.0
        esCarrier.attachModelElementObserver(object : ModelElementObserver() {
            override fun replicationEnded(modelElement: ModelElement) {
                capturedShipments =
                    esCarrier.getNumberOfDemandShipments(ihp)
                capturedWeight =
                    esCarrier.totalLoadWeightAccumulator(ihp)?.value ?: -1.0
                capturedCube =
                    esCarrier.totalLoadCubeAccumulator(ihp)?.value ?: -1.0
            }
        })

        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.simulate()

        // Sanity: replenishments did occur (the (s, Q) policy with
        // tight reorderPoint and steady demand fires regularly).
        assert(capturedShipments > 0.0) {
            "expected the ES→IHP edge to carry at least one shipment, got $capturedShipments"
        }
        // Per-shipment weight = Q × item.weight = 5 × 2.5 = 12.5.
        // Per-shipment cube  = Q × item.cube   = 5 × 1.5 = 7.5.
        // Accumulator equals shipments × per-shipment value.
        val expectedWeight = capturedShipments * 5.0 * 2.5
        val expectedCube = capturedShipments * 5.0 * 1.5
        assertEquals(expectedWeight, capturedWeight, 1e-9)
        assertEquals(expectedCube, capturedCube, 1e-9)
    }
}
