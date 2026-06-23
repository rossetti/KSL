package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Cost redesign — verifies the ordering invariant the rollup
 * architecture relies on: when the formulation's `replicationEnded()`
 * runs, every calculator's source-attached observer has *already*
 * fired, so the calculator's `lineResponses[*]` are populated.
 *
 * **What actually establishes the ordering** (audit finding F): the
 * calculator's observer fires on its *source's* `REPLICATION_ENDED`,
 * not on the calculator's own tree-walk position.  Correctness
 * therefore requires every source to precede the formulation in the
 * model's depth-first tree walk — which holds when the source is
 * *constructed before* the formulation.  In this test the source
 * `src` is deliberately constructed before the `RecordingFormulation`
 * (note the line order in the test body), satisfying the contract.
 * The companion test [CostFormulationOrderingGuardTest] exercises the
 * *violation* — topology constructed after the formulation — and
 * asserts the guard fails loud rather than silently reporting 0.
 */
class CalculatorOrderingTest {

    /**
     * Same shape as [CostCalculatorObserverTest.TestCalculator] but
     * the line value is the source's TW average × 100.0 for
     * unambiguous non-zero assertions.
     */
    private class TestCalculator(
        parent: ModelElement,
        private val src: TWResponse,
    ) : ModelElement(parent, "${src.name}:CostCalcOrd"), CostCalculator {

        override val source: ModelElement get() = src
        override val tier: NodeTier? get() = NodeTier.IHP

        private val myHolding = Response(this, "${src.name}:HoldingOrd")
        override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
            CostLine.Holding to myHolding,
        )

        private inner class Obs : ModelElementObserver() {
            override fun replicationEnded(modelElement: ModelElement) {
                myHolding.value = src.withinReplicationStatistic.weightedAverage * 100.0
            }
        }

        init { src.attachModelElementObserver(Obs()) }
    }

    /**
     * Test formulation that reads its single child calculator's
     * line Response in its own replicationEnded.  If the KSL tree
     * walk did NOT fire the child first, the read value would be 0.
     */
    private class RecordingFormulation(
        network: MultiEchelonNetwork,
        srcTW: TWResponse,
    ) : DefaultMultiEchelonCostFormulation(network, name = "Recording") {

        // Phase 2's skeleton has empty `calculators` — but for this
        // test we need a real child to observe.  Construct a
        // TestCalculator as a child of this formulation and store a
        // reference so we can read its Response in our own
        // replicationEnded.
        private val testCalc = TestCalculator(this, srcTW)

        /** Value of the child's Holding Response captured at OUR replicationEnded. */
        var capturedHolding: Double = -1.0
            private set

        override fun replicationEnded() {
            // Read the child's Response BEFORE delegating to super —
            // demonstrates that even at the very start of our
            // replicationEnded, the child's value is already there.
            capturedHolding =
                testCalc.lineResponses[CostLine.Holding]?.value ?: -1.0
            super.replicationEnded()
        }
    }

    @Test
    fun `calculator child fires before formulation parent replicationEnded`() {
        val m = Model("Ordering")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        // Constant TW source so the calculator's value is deterministic.
        val src = TWResponse(net, name = "Src", initialValue = 3.0)
        val f = RecordingFormulation(net, src)

        m.numberOfReplications = 1
        m.lengthOfReplication = 5.0
        m.simulate()

        // src is constant at 3.0 → TW average 3.0 → calculator writes
        // 3.0 × 100.0 = 300.0.  Captured at the formulation's
        // replicationEnded entry, which is AFTER the calculator's
        // observer fires.
        assertTrue(
            f.capturedHolding == 300.0,
            "expected formulation to see calculator's Holding == 300.0 " +
                "(implying calculator child fired first), got " +
                "${f.capturedHolding}",
        )
    }
}
