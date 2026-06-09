package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase-2 (cost redesign) — with the Phase-2 skeleton
 * [DefaultMultiEchelonCostFormulation] (empty calculator list), every
 * pre-allocated rollup Response sums an empty list and reports 0.0
 * at replication end.  Phase 3 fills the calculator list and the
 * rollups start carrying real values; until then, this test
 * documents the no-op-but-wired baseline.
 */
class CostFormulationSkeletonRollupTest {

    @Test
    fun `skeleton formulation rollups all report 0 at replication end`() {
        val m = Model("Skeleton")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val f = DefaultMultiEchelonCostFormulation(net)

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        // Every per-line rollup must exist (pre-allocated at
        // construction) and must report 0.0 because the calculator
        // list is empty.
        for (line in CostLine.all) {
            val r = f.byLineResponse(line)
                ?: error("byLineResponse($line) returned null")
            assertEquals(0.0, r.value, 1e-12,
                "per-line rollup for $line should be 0.0 in skeleton")
        }

        // Every per-tier rollup must exist and report 0.0.
        for (tier in NodeTier.all) {
            val r = f.byTierResponse(tier)
                ?: error("byTierResponse($tier) returned null")
            assertEquals(0.0, r.value, 1e-12,
                "per-tier rollup for $tier should be 0.0 in skeleton")
        }

        // Grand total = 0.0.
        assertEquals(0.0, f.totalCostResponse.value, 1e-12)
    }
}
