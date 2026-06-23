package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.spec.CostFormulationSpec
import ksl.modeling.supplychain.spec.CostParamsSpec
import ksl.modeling.supplychain.spec.DemandGeneratorSpec
import ksl.modeling.supplychain.spec.InventorySpec
import ksl.modeling.supplychain.spec.ItemSpec
import ksl.modeling.supplychain.spec.LimitsSpec
import ksl.modeling.supplychain.spec.NetworkSpec
import ksl.modeling.supplychain.spec.NodeSpec
import ksl.modeling.supplychain.spec.NodeType
import ksl.modeling.supplychain.spec.PolicySpec
import ksl.modeling.supplychain.spec.RVSpec
import ksl.modeling.supplychain.spec.ShipmentFormationSpec
import ksl.modeling.supplychain.spec.FormingOption
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.TransportStrategySpec
import ksl.modeling.supplychain.spec.validate
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-product feature-matrix audit with two folded invariant
 * families.  This is the coverage-first / seam-first pass that
 * complements the original depth-first per-subsystem audit: it walks
 * combinations of orthogonal features — transport strategy × inventory
 * policy × cross-dock × shipment formation — and asserts behavioural
 * invariants on each, rather than checking exact numbers.
 *
 * Why this exists: the load-carrier edge-cost bug lived in the single
 * untested matrix cell `PerIHPTimeBased × formation-on × cost-on` and
 * failed silently (a plausible 0.0, not a crash).  The invariants below
 * are designed to catch that whole bug *class*:
 *
 *  - **(#3) activity ⇒ cost (metamorphic).**  Under a time-based
 *    transport strategy with positive rates, a network that replenishes
 *    its stock **must** show positive Loading / Shipping / Unloading /
 *    ESLoading — and this must hold whether or not shipment formation is
 *    enabled (toggling a feature must not zero an unrelated metric).
 *    Under `SharedCarrier` those per-edge flow lines are 0 *by design*
 *    (no per-edge counters); the test pins that too.
 *  - **(#2) output coverage.**  Across the whole matrix, every cost line
 *    that the topologies can structurally produce must be driven > 0 in
 *    at least one scenario — so no producible output is left silently
 *    dead.
 */
class SupplyChainFeatureMatrixTest {

    private val flowLines = listOf(
        CostLine.Loading, CostLine.Shipping, CostLine.Unloading, CostLine.ESLoading,
    )
    private val alwaysActiveLines = listOf(
        CostLine.Holding, CostLine.InTransit, CostLine.Ordering,
    )

    /** A built-and-simulated scenario, with convenient per-line access. */
    private class Scenario(val label: String, val cost: DefaultMultiEchelonCostFormulation) {
        fun avg(line: CostLine): Double =
            cost.byLineResponse(line)?.acrossReplicationStatistic?.average ?: 0.0
    }

    private fun buildSpec(
        strategy: TransportStrategySpec,
        policyKind: String,
        withCD: Boolean,
        formationEnabled: Boolean = false,
        formation: ShipmentFormationSpec? = null,
    ): NetworkSpec {
        val timeBased = strategy != TransportStrategySpec.SharedCarrier
        val tt = if (timeBased) RVSpec.Constant(1.0) else null
        val esTt = if (timeBased) RVSpec.Constant(3.0) else null

        fun policy(reorder: Int, up: Int): PolicySpec = when (policyKind) {
            "sQ" -> PolicySpec.SQ(reorder, up)
            "sS" -> PolicySpec.SS(reorder, reorder + up)
            "sSPeriodic" -> PolicySpec.SSPeriodic(reorder, reorder + up, RVSpec.Constant(5.0))
            else -> error("unknown policy kind $policyKind")
        }

        val item = ItemSpec("A", RVSpec.Exponential(1.0, 1), weight = 2.0, cube = 1.0, unitCost = 5.0)
        val retailerParent = if (withCD) "XD" else "WH"
        val nodes = buildList {
            add(
                NodeSpec(
                    "WH", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER,
                    transportTimeFromParent = esTt,
                    enableShipmentFormation = formationEnabled,
                    inventory = listOf(InventorySpec("A", policy(8, 30), 40)),
                ),
            )
            if (withCD) add(NodeSpec("XD", NodeType.CD, "WH", transportTimeFromParent = tt))
            for (i in 1..2) {
                add(
                    NodeSpec(
                        "R$i", NodeType.IHP, retailerParent,
                        transportTimeFromParent = tt,
                        shipmentFormationFromParent = formation,
                        inventory = listOf(InventorySpec("A", policy(3, 7), 15)),
                    ),
                )
            }
        }
        val dgs = (1..2).map { DemandGeneratorSpec("R$it", "A", RVSpec.Exponential(1.0, 9 + it)) }
        return NetworkSpec(
            name = "M", transportStrategy = strategy, items = listOf(item),
            nodes = nodes, demandGenerators = dgs,
            costFormulations = listOf(
                CostFormulationSpec.Default(
                    name = "cost",
                    params = CostParamsSpec(
                        carryingRate = 0.1, orderingCost = 5.0,
                        loadingCost = 40.0, shippingCost = 15.0,
                        unloadingCost = 30.0, esLoadingCost = 40.0,
                    ),
                ),
            ),
        )
    }

    private fun run(label: String, spec: NetworkSpec): Scenario {
        assertTrue(spec.validate().isEmpty(), "$label: spec invalid: ${spec.validate()}")
        val m = Model("matrix-$label")
        val result = SupplyChainBuilder.build(m, spec)
        m.numberOfReplications = 2
        m.lengthOfReplication = 800.0
        m.lengthOfReplicationWarmUp = 200.0
        m.simulate()
        val cost = result.network.costFormulations.first() as DefaultMultiEchelonCostFormulation
        return Scenario(label, cost)
    }

    /** Strategy-aware invariant assertions, run on every scenario. */
    private fun assertInvariants(s: Scenario, timeBased: Boolean, everNonZero: MutableSet<CostLine>) {
        // Always-active lines: stock is held, replenishment is ordered,
        // and the warehouse waits on the ES lead time (so on-order > 0).
        for (l in alwaysActiveLines) {
            assertTrue(s.avg(l) > 0.0, "${s.label}: $l should be > 0 (was ${s.avg(l)})")
        }
        if (timeBased) {
            // (#3) the load-carrier-bug invariant: replenishment flow ⇒ flow cost.
            for (l in flowLines) {
                assertTrue(s.avg(l) > 0.0, "${s.label}: $l should be > 0 under a time-based strategy (was ${s.avg(l)})")
            }
        } else {
            // SharedCarrier: per-edge flow lines are 0 by design.
            for (l in flowLines) {
                assertEquals(0.0, s.avg(l), 0.0, "${s.label}: $l should be 0 under SharedCarrier")
            }
        }
        for (l in CostLine.all) if (s.avg(l) > 0.0) everNonZero += l
    }

    @Test
    fun `feature matrix satisfies cost invariants and output coverage`() {
        val strategies = mapOf(
            "shared" to TransportStrategySpec.SharedCarrier,
            "perIHP" to TransportStrategySpec.PerIHPTimeBased,
            "network" to TransportStrategySpec.NetworkTimeBased,
        )
        val policies = listOf("sQ", "sS", "sSPeriodic")
        val everNonZero = mutableSetOf<CostLine>()

        // Core grid: every (strategy × policy) with no cross-dock, plus
        // every strategy with a cross-dock (policy sS).
        for ((sName, strategy) in strategies) {
            val timeBased = strategy != TransportStrategySpec.SharedCarrier
            for (policy in policies) {
                val label = "$sName/$policy"
                assertInvariants(run(label, buildSpec(strategy, policy, withCD = false)), timeBased, everNonZero)
            }
            val cdLabel = "$sName/sS+CD"
            assertInvariants(run(cdLabel, buildSpec(strategy, "sS", withCD = true)), timeBased, everNonZero)
        }

        // Formation sub-matrix (PerIHPTimeBased only): each forming option
        // must still accrue flow costs and shipment-builder holding.
        val formations = mapOf(
            "count" to ShipmentFormationSpec(FormingOption.COUNT, countLimit = 2),
            "weight" to ShipmentFormationSpec(FormingOption.WEIGHT, weightLimits = LimitsSpec(1.0, 1000.0)),
            "cube" to ShipmentFormationSpec(FormingOption.CUBE, cubeLimits = LimitsSpec(1.0, 1000.0)),
        )
        for ((fName, formation) in formations) {
            val label = "perIHP/sS/formation=$fName"
            val s = run(
                label,
                buildSpec(
                    TransportStrategySpec.PerIHPTimeBased, "sS", withCD = false,
                    formationEnabled = true, formation = formation,
                ),
            )
            assertInvariants(s, timeBased = true, everNonZero)
            // COUNT(2) forces a demand to wait for its partner, so the
            // builder holds stock; WEIGHT/CUBE with a min-of-1 form on the
            // first demand (nothing queues), so builder holding is ~0 —
            // correct, not asserted.
            if (fName == "count") {
                assertTrue(
                    s.avg(CostLine.ShipmentBuilderHolding) > 0.0,
                    "$label: ShipmentBuilderHolding should be > 0 when demands queue for formation",
                )
            }
        }

        // (#2) coverage: every structurally-producible line was hit > 0
        // in at least one scenario.
        val mustCover = listOf(
            CostLine.Holding, CostLine.InTransit, CostLine.Ordering,
            CostLine.Loading, CostLine.Shipping, CostLine.Unloading,
            CostLine.ESLoading, CostLine.ShipmentBuilderHolding,
        )
        for (l in mustCover) {
            assertTrue(l in everNonZero, "no scenario produced a non-zero $l — possible silently-dead output")
        }
    }

    @Test
    fun `toggling shipment formation must not zero out per-edge flow costs`() {
        val off = run(
            "metamorphic/off",
            buildSpec(TransportStrategySpec.PerIHPTimeBased, "sS", withCD = false),
        )
        val on = run(
            "metamorphic/on",
            buildSpec(
                TransportStrategySpec.PerIHPTimeBased, "sS", withCD = false,
                formationEnabled = true,
                formation = ShipmentFormationSpec(FormingOption.COUNT, countLimit = 2),
            ),
        )
        for (l in listOf(CostLine.Loading, CostLine.Shipping, CostLine.Unloading)) {
            assertTrue(off.avg(l) > 0.0, "formation OFF: $l should be > 0 (was ${off.avg(l)})")
            assertTrue(
                on.avg(l) > 0.0,
                "formation ON: $l should be > 0 (was ${on.avg(l)}) — the load-carrier shipment-count regression",
            )
        }
    }
}
