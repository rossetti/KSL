package ksl.modeling.supplychain.spec

import ksl.modeling.supplychain.cost.CostLine
import ksl.modeling.supplychain.cost.CostParams
import ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
import ksl.modeling.supplychain.cost.NodeTier
import ksl.modeling.supplychain.cost.PerNodeIHPCostFormulation
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the cost-formulation spec wiring (DSL plan Phase D5 /
 * cost-redesign Phase 6): a [CostFormulationSpec] builds the matching
 * runtime formulation, multiple named formulations coexist and report
 * independently, and per-node overrides actually change the targeted
 * node's cost.
 *
 * Parity tests build the *same* network two ways — the cost formulation
 * declared in the spec vs. attached by hand after building the
 * cost-free spec — so element creation order, streams, and run
 * parameters are identical and the responses are bit-identical.
 */
class CostFormulationSpecTest {

    /** A 2-tier PerIHPTimeBased network; cost formulations supplied by the caller. */
    private fun baseSpec(cost: List<CostFormulationSpec>) = NetworkSpec(
        name = "Cost",
        transportStrategy = TransportStrategySpec.PerIHPTimeBased,
        items = listOf(ItemSpec("A", RVSpec.Exponential(1.0, 1), unitCost = 10.0)),
        nodes = listOf(
            NodeSpec(
                "Warehouse", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER,
                transportTimeFromParent = RVSpec.Constant(3.0),
                inventory = listOf(InventorySpec("A", PolicySpec.SQ(5, 20), 30)),
            ),
            NodeSpec(
                "R1", NodeType.IHP, "Warehouse",
                transportTimeFromParent = RVSpec.Constant(1.0),
                inventory = listOf(InventorySpec("A", PolicySpec.SS(3, 10), 10)),
            ),
            NodeSpec(
                "R2", NodeType.IHP, "Warehouse",
                transportTimeFromParent = RVSpec.Constant(1.0),
                inventory = listOf(InventorySpec("A", PolicySpec.SS(2, 8), 10)),
            ),
        ),
        demandGenerators = listOf(
            DemandGeneratorSpec("R1", "A", RVSpec.Exponential(2.0, 10)),
            DemandGeneratorSpec("R2", "A", RVSpec.Exponential(1.5, 11)),
        ),
        costFormulations = cost,
    )

    private fun run(m: Model) {
        m.numberOfReplications = 3
        m.lengthOfReplication = 1500.0
        m.lengthOfReplicationWarmUp = 300.0
        m.simulate()
    }

    private fun responseAverages(m: Model): List<Double> =
        m.responses.map { it.acrossReplicationStatistic.average }.sorted()

    // -- Default spec parity --------------------------------------------

    @Test
    fun `a Default cost spec matches a hand-attached default formulation`() {
        val params = CostParamsSpec(carryingRate = 0.15, backorderRate = 50.0, stockoutCost = 5.0)

        val mSpec = Model("cost-spec")
        SupplyChainBuilder.build(
            mSpec, baseSpec(listOf(CostFormulationSpec.Default(name = "C", params = params))),
        )
        run(mSpec)

        val mHand = Model("cost-hand")
        val result = SupplyChainBuilder.build(mHand, baseSpec(emptyList()))
        DefaultMultiEchelonCostFormulation(
            result.network,
            CostParams(carryingRate = 0.15, backorderRate = 50.0, stockoutCost = 5.0),
            "C",
        )
        run(mHand)

        val a = responseAverages(mSpec)
        val b = responseAverages(mHand)
        assertEquals(a.size, b.size, "response counts differ")
        for (i in a.indices) assertEquals(a[i], b[i], 0.0, "response #$i differs")
    }

    // -- PerNodeIHP spec parity -----------------------------------------

    @Test
    fun `a PerNodeIHP cost spec matches a hand-attached per-node formulation`() {
        val specCost = CostFormulationSpec.PerNodeIHP(
            name = "PN",
            default = CostParamsSpec(carryingRate = 0.10),
            overrides = mapOf(
                "Warehouse" to CostParamsSpec(carryingRate = 0.10, unloadingCost = 75.0),
                "R1" to CostParamsSpec(carryingRate = 0.50),
            ),
        )

        val mSpec = Model("pn-spec")
        SupplyChainBuilder.build(mSpec, baseSpec(listOf(specCost)))
        run(mSpec)

        val mHand = Model("pn-hand")
        val result = SupplyChainBuilder.build(mHand, baseSpec(emptyList()))
        PerNodeIHPCostFormulation(
            result.network,
            defaultParams = CostParams(carryingRate = 0.10),
            overrides = mapOf(
                "Warehouse" to CostParams(carryingRate = 0.10, unloadingCost = 75.0),
                "R1" to CostParams(carryingRate = 0.50),
            ),
            name = "PN",
        )
        run(mHand)

        val a = responseAverages(mSpec)
        val b = responseAverages(mHand)
        assertEquals(a.size, b.size, "response counts differ")
        for (i in a.indices) assertEquals(a[i], b[i], 0.0, "response #$i differs")
    }

    // -- per-node override actually changes the targeted node's cost ----

    @Test
    fun `raising one node's carrying rate raises the IHP-tier holding cost`() {
        val mLow = Model("low")
        val rLow = SupplyChainBuilder.build(mLow, baseSpec(emptyList()))
        val low = DefaultMultiEchelonCostFormulation(rLow.network, CostParams(carryingRate = 0.10), "F")
        run(mLow)

        val mHigh = Model("high")
        val rHigh = SupplyChainBuilder.build(mHigh, baseSpec(emptyList()))
        val high = PerNodeIHPCostFormulation(
            rHigh.network,
            defaultParams = CostParams(carryingRate = 0.10),
            overrides = mapOf("Warehouse" to CostParams(carryingRate = 5.0)),
            name = "F",
        )
        run(mHigh)

        val lowHolding = low.byTierAndLineResponse(NodeTier.IHP, CostLine.Holding)
        val highHolding = high.byTierAndLineResponse(NodeTier.IHP, CostLine.Holding)
        assertNotNull(lowHolding); assertNotNull(highHolding)
        assertTrue(lowHolding.acrossReplicationStatistic.average > 0.0, "baseline should hold stock")
        assertTrue(
            highHolding.acrossReplicationStatistic.average >
                lowHolding.acrossReplicationStatistic.average,
            "overriding Warehouse carryingRate 0.10 -> 5.0 must raise IHP-tier holding cost " +
                "(low=${lowHolding.acrossReplicationStatistic.average}, " +
                "high=${highHolding.acrossReplicationStatistic.average})",
        )
    }

    // -- multiple formulations coexist and report independently ---------

    @Test
    fun `two named formulations both report and the higher rate costs more`() {
        val spec = baseSpec(
            listOf(
                CostFormulationSpec.Default(name = "standard", params = CostParamsSpec(carryingRate = 0.10)),
                CostFormulationSpec.Default(name = "highCarrying", params = CostParamsSpec(carryingRate = 1.00)),
            ),
        )
        val m = Model("multi")
        SupplyChainBuilder.build(m, spec)
        run(m)

        val stdTotal = m.responses.first { it.name == "standard:GrandTotal" }
        val highTotal = m.responses.first { it.name == "highCarrying:GrandTotal" }
        // Both formulations observed the same sample path; the higher
        // carrying rate yields the larger total cost.
        assertTrue(stdTotal.acrossReplicationStatistic.average > 0.0)
        assertTrue(
            highTotal.acrossReplicationStatistic.average > stdTotal.acrossReplicationStatistic.average,
            "highCarrying total (${highTotal.acrossReplicationStatistic.average}) should exceed " +
                "standard total (${stdTotal.acrossReplicationStatistic.average})",
        )
    }

    // -- validation of multi-formulation naming -------------------------

    @Test
    fun `multiple formulations without names are rejected`() {
        val spec = baseSpec(
            listOf(CostFormulationSpec.Default(), CostFormulationSpec.Default()),
        )
        val errors = spec.validate()
        assertTrue(errors.any { it.message.contains("requires every") }, "got: $errors")
    }

    @Test
    fun `duplicate formulation names are rejected`() {
        val spec = baseSpec(
            listOf(
                CostFormulationSpec.Default(name = "dup"),
                CostFormulationSpec.Default(name = "dup"),
            ),
        )
        val errors = spec.validate()
        assertTrue(errors.any { it.message.contains("duplicate cost formulation name 'dup'") }, "got: $errors")
    }

    @Test
    fun `a single unnamed formulation is allowed`() {
        val spec = baseSpec(listOf(CostFormulationSpec.Default()))
        assertTrue(spec.validate().isEmpty(), "got: ${spec.validate()}")
    }
}
