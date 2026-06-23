package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase-2 (cost redesign) — verifies the multi-attach mechanism on
 * [MultiEchelonNetwork].  Two distinct
 * [DefaultMultiEchelonCostFormulation]s are constructed against the
 * same network with different [CostParams]; both must register
 * automatically (via the formulation's init block), both must
 * survive a `simulate()` call, and both must produce independent
 * Response instances.
 */
class CostFormulationMultiAttachTest {

    @Test
    fun `two formulations attach to one network and survive simulate`() {
        val m = Model("MultiAttach")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )

        // Auto-attach happens in each formulation's init block.
        val standardParams = CostParams(carryingRate = 0.10)
        val highRateParams = CostParams(carryingRate = 0.25)
        val standard = DefaultMultiEchelonCostFormulation(
            net, standardParams, name = "Standard",
        )
        val highRate = DefaultMultiEchelonCostFormulation(
            net, highRateParams, name = "HighRate",
        )

        // Both formulations registered with the network.
        val attached = net.costFormulations
        assertEquals(2, attached.size)
        assertTrue(standard in attached)
        assertTrue(highRate in attached)

        // Independent params per formulation.
        assertSame(standardParams, standard.params)
        assertSame(highRateParams, highRate.params)

        // Independent rollup Response instances per formulation.
        assertTrue(
            standard.totalCostResponse !== highRate.totalCostResponse,
            "expected independent totalCostResponse instances",
        )

        // Sanity: simulate runs without crashing the registry.
        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()
    }

    @Test
    fun `attachCostFormulation is idempotent`() {
        val m = Model("Idempotent")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(sc, name = "Net")

        // Construction auto-attaches.
        val f = DefaultMultiEchelonCostFormulation(net)
        // Explicit re-attach should be a no-op.
        net.attachCostFormulation(f)
        net.attachCostFormulation(f)

        assertEquals(1, net.costFormulations.size)
    }
}
