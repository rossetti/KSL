package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

/**
 * Audit finding F + G regression: the cost formulation's rollup is
 * correct only when the formulation is constructed *after* the full
 * topology, so that every cost source precedes the formulation in the
 * model's tree walk.  Two guards now make a violation fail loud
 * instead of silently reporting 0:
 *
 *  - **G (coverage)**: `beforeExperiment` re-measures the topology and
 *    throws if a node / edge / inventory / demand generator was added
 *    after the formulation was built (such additions get no calculator).
 *  - **F (ordering)**: `replicationEnded` throws if any calculator's
 *    source has not reached `REPLICATION_ENDED` when the rollup runs
 *    (the source's observer would not yet have populated the
 *    calculator's Response).
 *
 * The happy-path (formulation built last) is covered by the broader
 * cost tests; this test exercises the *violation*.
 */
class CostFormulationOrderingGuardTest {

    @Test
    fun `adding an IHP after the formulation fails loud at simulation start`() {
        val m = Model("OrderGuard.AddIHP")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25))
        val a = net.addInventoryHoldingPoint("A")
        a.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(a, ConstantRV(0.25))
        net.attachDemandGenerator(
            a, item, ConstantRV(1.0), name = "DG-A",
            transportTime = ConstantRV.ZERO,
        )

        // Formulation built here — but topology grows afterward.
        DefaultMultiEchelonCostFormulation(net)

        // Illegally add another IHP AFTER the formulation.  It gets no
        // calculator; the coverage guard must catch the mismatch.
        val b = net.addInventoryHoldingPoint("B")
        b.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToSupplier(a, b, ConstantRV(0.5))

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        val ex = assertThrows<IllegalStateException> { m.simulate() }
        assertTrue(
            ex.message!!.contains("topology changed", ignoreCase = true),
            "expected a topology-coverage guard message, got: ${ex.message}",
        )
    }

    @Test
    fun `adding a demand generator after the formulation fails loud`() {
        val m = Model("OrderGuard.AddDG")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25))
        val a = net.addInventoryHoldingPoint("A")
        a.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(a, ConstantRV(0.25))

        DefaultMultiEchelonCostFormulation(net)

        // Add a demand generator after the formulation.
        net.attachDemandGenerator(
            a, item, ConstantRV(1.0), name = "DG-late",
            transportTime = ConstantRV.ZERO,
        )

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        assertThrows<IllegalStateException> { m.simulate() }
    }

    @Test
    fun `formulation built last simulates cleanly`() {
        // Control: the documented happy path must NOT trip the guards.
        val m = Model("OrderGuard.Clean")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(0.25))
        val a = net.addInventoryHoldingPoint("A")
        a.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(a, ConstantRV(0.25))
        net.attachDemandGenerator(
            a, item, ConstantRV(1.0), name = "DG-A",
            transportTime = ConstantRV.ZERO,
        )
        // Built last — correct.
        val f = DefaultMultiEchelonCostFormulation(net)

        m.numberOfReplications = 2
        m.lengthOfReplication = 10.0
        m.simulate()  // must not throw
        assertTrue(f.totalCostResponse.value >= 0.0)
    }
}
