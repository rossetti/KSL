package ksl.modeling.supplychain.cost

import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Phase-2 (cost redesign) — verifies the canonical
 * [CostCalculator] implementation shape: an inner-class
 * `ModelElementObserver` attached to a single source, populating
 * the calculator's line-item Responses on the source's
 * `REPLICATION_ENDED` notification.
 *
 * The test uses a hand-rolled calculator that observes a single
 * [TWResponse] source and writes one line-item Response.  No
 * [CostFormulation] is involved — Phase 2's infrastructure is
 * tested at the calculator-pattern level here, separately from the
 * formulation-attach mechanism (which has its own test).
 */
class CostCalculatorObserverTest {

    /**
     * A trivial calculator that observes one [TWResponse] and emits
     * its time-weighted average × 10.0 as a single "Holding" line.
     */
    private class TestCalculator(
        parent: ModelElement,
        private val src: TWResponse,
    ) : ModelElement(parent, "${src.name}:CostCalc"), CostCalculator {

        override val source: ModelElement get() = src
        override val tier: NodeTier? get() = NodeTier.IHP

        private val myHolding = Response(this, "${src.name}:HoldingTest")
        override val lineResponses: Map<CostLine, ResponseCIfc> = mapOf(
            CostLine.Holding to myHolding,
        )

        private inner class Obs : ModelElementObserver() {
            override fun replicationEnded(modelElement: ModelElement) {
                val avg = src.withinReplicationStatistic.weightedAverage
                myHolding.value = avg * 10.0
            }
        }

        init { src.attachModelElementObserver(Obs()) }
    }

    @Test
    fun `calculator observer fires at source REPLICATION_ENDED and writes line Response`() {
        val m = Model("CalcObs")
        val src = TWResponse(m, name = "Src", initialValue = 5.0)
        val calc = TestCalculator(m, src)

        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()

        // src is constant at 5.0 throughout the replication, so the
        // time-weighted average is 5.0; calculator multiplies by 10.0
        // and writes 50.0 into the Holding line.
        val holdingValue =
            calc.lineResponses[CostLine.Holding]?.value
                ?: error("Holding response missing")
        assertEquals(50.0, holdingValue, 1e-12)
    }

    @Test
    fun `calculator exposes its declared source and tier`() {
        val m = Model("CalcSourceTier")
        val src = TWResponse(m, name = "Src")
        val calc = TestCalculator(m, src)
        assertSame(src, calc.source)
        assertSame(NodeTier.IHP, calc.tier)
    }
}
