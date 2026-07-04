package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the cost-formulation additions from the supplychain-controls effort:
 * per-node (location) total-cost rollup Responses, and the network-level rate
 * controls (carryingRate, orderingCost, backorderRate, stockoutCost) resolved live
 * at each replication end.
 */
class CostFormulationControlsTest {

    /** The PerIHPTimeBased single-node fixture from MultiEchelonNetworkCostTest. */
    private class Fixture(formulationBuilder: (MultiEchelonNetwork) -> DefaultMultiEchelonCostFormulation) {
        val model = Model("CostControls")
        val net: MultiEchelonNetwork
        val formulation: DefaultMultiEchelonCostFormulation

        init {
            val sc = SupplyChainModel(model, name = "SC")
            net = MultiEchelonNetwork(
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
            formulation = formulationBuilder(net)
            model.numberOfReplications = 1
            model.lengthOfReplication = 30.0
        }
    }

    // ── B: per-node rollups ───────────────────────────────────────────────────

    @Test
    @DisplayName("Per-node totals exist, and node totals plus ES equal the grand total")
    fun perNodeRollupsSumToGrandTotal() {
        val f = Fixture { net -> DefaultMultiEchelonCostFormulation(net, name = "Costs") }
        f.model.simulate()

        assertTrue("P" in f.formulation.trackedNodeNames) {
            "expected node P to be tracked; got ${f.formulation.trackedNodeNames}"
        }
        val nodeTotal = f.formulation.byNodeResponse("P")
        assertNotNull(nodeTotal)
        assertTrue(nodeTotal!!.value > 0.0, "node P must accrue cost")

        // The only calculators without an owning node are the external supplier's
        // own outbound, whose contribution is the ES tier rollup — so node totals
        // plus the ES tier must reproduce the grand total.
        val esTier = f.formulation.byTierResponse(NodeTier.ES)!!.value
        assertEquals(
            f.formulation.totalCostResponse.value,
            nodeTotal.value + esTier,
            1e-9,
            "node totals + ES-owned cost must equal the grand total"
        )
        // and the convenience name points at the grand-total Response
        assertEquals("Costs:GrandTotal", f.formulation.totalCostResponseName)
    }

    // ── C: network-level rate controls ────────────────────────────────────────

    @Test
    @DisplayName("Rate controls are discoverable and zeroing them zeroes their cost lines")
    fun rateControlsAffectComputedCost() {
        val f = Fixture { net -> DefaultMultiEchelonCostFormulation(net, name = "Costs") }
        val controls = f.model.controls()
        val keys = controls.controlKeys()
        for (key in listOf("Costs.carryingRate", "Costs.orderingCost",
                "Costs.backorderRate", "Costs.stockoutCost")) {
            assertTrue(key in keys) { "expected control key '$key'; got $keys" }
        }

        // Zero the inventory-side rates by key; the flow-side rates (loading,
        // shipping, unloading, ES loading) are untouched.
        controls.control("Costs.carryingRate")!!.value = 0.0
        controls.control("Costs.orderingCost")!!.value = 0.0
        controls.control("Costs.backorderRate")!!.value = 0.0
        controls.control("Costs.stockoutCost")!!.value = 0.0
        f.model.simulate()

        assertEquals(0.0, f.formulation.byLineResponse(CostLine.Holding)!!.value, 1e-12)
        assertEquals(0.0, f.formulation.byLineResponse(CostLine.InTransit)!!.value, 1e-12)
        assertEquals(0.0, f.formulation.byLineResponse(CostLine.Ordering)!!.value, 1e-12)
        assertEquals(0.0, f.formulation.byLineResponse(CostLine.Backorder)!!.value, 1e-12)
        assertEquals(0.0, f.formulation.byLineResponse(CostLine.Stockout)!!.value, 1e-12)
        // flow lines still accrue — the controls changed only the four rates
        assertTrue(f.formulation.totalCostResponse.value > 0.0,
            "flow-side costs must remain after zeroing the inventory-side rates")
    }

    @Test
    @DisplayName("Per-node overrides take precedence; non-overridden nodes follow the live network-level rates")
    fun perNodeOverridePrecedence() {
        // Node P carries a construction-time override; zeroing the network-level
        // carrying rate by control must NOT zero P's holding cost.
        val overridden = Fixture { net ->
            PerNodeIHPCostFormulation(
                net, defaultParams = CostParams(),
                overrides = mapOf("P" to CostParams(carryingRate = 0.5)),
                name = "Costs"
            )
        }
        overridden.model.controls().control("Costs.carryingRate")!!.value = 0.0
        overridden.model.simulate()
        assertTrue(overridden.formulation.byLineResponse(CostLine.Holding)!!.value > 0.0,
            "the overridden node's holding cost must survive the network-level zero")

        // Without an override, the resolver falls back to the LIVE bundle: the
        // same control write zeroes the holding cost.
        val fallback = Fixture { net ->
            PerNodeIHPCostFormulation(net, defaultParams = CostParams(), name = "Costs")
        }
        fallback.model.controls().control("Costs.carryingRate")!!.value = 0.0
        fallback.model.simulate()
        assertEquals(0.0, fallback.formulation.byLineResponse(CostLine.Holding)!!.value, 1e-12,
            "non-overridden nodes must follow the live network-level rate")
    }

    /** Probe that attempts a rate change mid-replication. */
    private class MidRunRateProbe(
        parent: ModelElement,
        private val attempt: () -> Unit
    ) : ModelElement(parent) {
        var gateThrew: Boolean = false
            private set

        override fun initialize() {
            schedule(this::tryChange, 1.0)
        }

        private fun tryChange(event: KSLEvent<Nothing>) {
            try {
                attempt()
            } catch (_: IllegalArgumentException) {
                gateThrew = true
            }
        }
    }

    @Test
    @DisplayName("Rate controls cannot be changed during a replication")
    fun ratesGatedMidReplication() {
        val f = Fixture { net -> DefaultMultiEchelonCostFormulation(net, name = "Costs") }
        // pre-run changes are allowed
        f.formulation.carryingRate = 0.2
        assertEquals(0.2, f.formulation.currentParams.carryingRate, 0.0)

        val probe = MidRunRateProbe(f.net) { f.formulation.carryingRate = 9.9 }
        f.model.simulate()
        assertTrue(probe.gateThrew, "changing a rate mid-replication must be rejected")
        assertEquals(0.2, f.formulation.currentParams.carryingRate, 0.0,
            "the rejected mid-run change must not alter the rate")
    }
}
