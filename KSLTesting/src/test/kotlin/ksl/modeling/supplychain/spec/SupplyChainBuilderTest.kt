package ksl.modeling.supplychain.spec

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [SupplyChainBuilder] covering the spec-to-runtime
 * mappings the parity tests do not exercise: RV materialization,
 * periodic policy, the three transport strategies, shipment formation,
 * the default cost formulation, and the build-time failure paths.
 */
class SupplyChainBuilderTest {

    // -- RV materialization ---------------------------------------------

    @Test
    fun `materialize maps every RVSpec variant to its KSL random variable`() {
        val c = SupplyChainBuilder.materialize(RVSpec.Constant(7.5))
        assertIs<ConstantRV>(c)
        assertEquals(7.5, c.constVal)

        val e = SupplyChainBuilder.materialize(RVSpec.Exponential(2.0, stream = 3))
        assertIs<ExponentialRV>(e)
        assertEquals(2.0, e.mean)
        assertEquals(3, e.streamNumber)

        val u = SupplyChainBuilder.materialize(RVSpec.Uniform(1.0, 4.0, stream = 5))
        assertIs<UniformRV>(u)
        assertEquals(1.0, u.min)
        assertEquals(4.0, u.max)
        assertEquals(5, u.streamNumber)

        val t = SupplyChainBuilder.materialize(RVSpec.Triangular(1.0, 2.0, 5.0, stream = 6))
        assertIs<TriangularRV>(t)
        assertEquals(1.0, t.min)
        assertEquals(2.0, t.mode)
        assertEquals(5.0, t.max)
        assertEquals(6, t.streamNumber)

        val l = SupplyChainBuilder.materialize(RVSpec.Lognormal(3.0, 1.5, stream = 7))
        assertIs<LognormalRV>(l)
        assertEquals(3.0, l.mean)
        assertEquals(1.5, l.variance)
        assertEquals(7, l.streamNumber)
    }

    // -- a minimal buildable spec ---------------------------------------

    private fun minimalSpec(
        policy: PolicySpec = PolicySpec.SQ(2, 10),
        strategy: TransportStrategySpec = TransportStrategySpec.SharedCarrier,
        transportTime: RVSpec? = null,
        costFormulations: List<CostFormulationSpec> = emptyList(),
        formation: ShipmentFormationSpec? = null,
        enableFormation: Boolean = false,
    ) = NetworkSpec(
        name = "Net",
        transportStrategy = strategy,
        items = listOf(ItemSpec("A", RVSpec.Exponential(1.0, 1))),
        nodes = listOf(
            NodeSpec(
                "WH", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER,
                inventory = listOf(InventorySpec("A", PolicySpec.SQ(5, 20), 30)),
                enableShipmentFormation = enableFormation,
            ),
            NodeSpec(
                "R", NodeType.IHP, "WH",
                transportTimeFromParent = transportTime,
                inventory = listOf(InventorySpec("A", policy, 10)),
                shipmentFormationFromParent = formation,
            ),
        ),
        demandGenerators = listOf(DemandGeneratorSpec("R", "A", RVSpec.Exponential(2.0, 5))),
        costFormulations = costFormulations,
    )

    private fun shortRun(m: Model) {
        m.numberOfReplications = 2
        m.lengthOfReplication = 500.0
        m.simulate()
    }

    // -- periodic policy -------------------------------------------------

    @Test
    fun `periodic policy with a constant review interval builds and simulates`() {
        val m = Model("periodic")
        val spec = minimalSpec(policy = PolicySpec.SSPeriodic(2, 8, RVSpec.Constant(5.0)))
        val result = SupplyChainBuilder.build(m, spec)
        assertEquals(2, result.network.getInventoryHoldingPoints().size)
        shortRun(m) // must not throw
    }

    @Test
    fun `periodic policy with a non-constant review interval is rejected`() {
        val m = Model("periodic-bad")
        val spec = minimalSpec(policy = PolicySpec.SSPeriodic(2, 8, RVSpec.Exponential(5.0, 9)))
        val ex = assertThrows<IllegalStateException> { SupplyChainBuilder.build(m, spec) }
        assertTrue(ex.message!!.contains("constant"), "message: ${ex.message}")
    }

    // -- transport strategies -------------------------------------------

    @Test
    fun `NetworkTimeBased strategy builds a shared carrier and simulates`() {
        val m = Model("network-tb")
        val spec = minimalSpec(
            strategy = TransportStrategySpec.NetworkTimeBased,
            transportTime = RVSpec.Constant(1.5),
        )
        val result = SupplyChainBuilder.build(m, spec)
        shortRun(m)
        assertEquals(2, result.network.getInventoryHoldingPoints().size)
    }

    @Test
    fun `PerIHPTimeBased strategy with per-edge transport time builds and simulates`() {
        val m = Model("perihp")
        val spec = minimalSpec(
            strategy = TransportStrategySpec.PerIHPTimeBased,
            transportTime = RVSpec.Constant(2.0),
        )
        SupplyChainBuilder.build(m, spec)
        shortRun(m)
    }

    // -- shipment formation ---------------------------------------------

    @Test
    fun `shipment formation under PerIHPTimeBased builds and simulates`() {
        val m = Model("formation")
        val spec = minimalSpec(
            strategy = TransportStrategySpec.PerIHPTimeBased,
            transportTime = RVSpec.Constant(1.0),
            enableFormation = true,
            formation = ShipmentFormationSpec(FormingOption.COUNT, countLimit = 3),
        )
        SupplyChainBuilder.build(m, spec)
        shortRun(m)
    }

    // -- cost formulation (built last) ----------------------------------

    @Test
    fun `default cost formulation is attached and produces a grand-total response`() {
        val m = Model("cost")
        val spec = minimalSpec(
            costFormulations = listOf(
                CostFormulationSpec.Default(name = "C", params = CostParamsSpec(carryingRate = 0.2)),
            ),
        )
        val result = SupplyChainBuilder.build(m, spec)
        assertEquals(1, result.network.costFormulations.size)
        shortRun(m)
        assertTrue(
            m.responses.any { it.name.contains("GrandTotal") },
            "expected a cost grand-total response; got ${m.responses.map { it.name }}",
        )
    }

    @Test
    fun `PerNodeIHP cost formulation builds and attaches`() {
        val m = Model("pernode")
        val spec = minimalSpec(
            costFormulations = listOf(
                CostFormulationSpec.PerNodeIHP(
                    name = "PN",
                    default = CostParamsSpec(),
                    overrides = mapOf("WH" to CostParamsSpec(carryingRate = 0.3)),
                ),
            ),
        )
        val result = SupplyChainBuilder.build(m, spec)
        assertEquals(1, result.network.costFormulations.size)
        shortRun(m)
        assertTrue(m.responses.any { it.name == "PN:GrandTotal" })
    }

    // -- validation gate -------------------------------------------------

    @Test
    fun `build rejects an invalid spec with a listed error message`() {
        val m = Model("invalid")
        val bad = NetworkSpec(
            name = "Bad",
            items = listOf(ItemSpec("A", RVSpec.Constant(1.0))),
            nodes = listOf(NodeSpec("R", NodeType.IHP, "ghost")), // dangling parent, no root
        )
        val ex = assertThrows<IllegalArgumentException> { SupplyChainBuilder.build(m, bad) }
        assertTrue(ex.message!!.contains("unknown parent 'ghost'"), "message: ${ex.message}")
    }
}
