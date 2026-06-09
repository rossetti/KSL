package ksl.modeling.variable

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for [AggregateResponse]: echo semantics, pooled within-
 * replication statistics, and detach behavior.
 */
class AggregateResponseTest {

    /**
     * Minimal model element that exposes [pushObservations] so tests
     * can drive observations onto Response sources directly.
     */
    private class TestPusher(parent: ModelElement, name: String) :
        ModelElement(parent, name) {
        val response: Response = Response(this, "$name:Resp")
        fun pushObservations(vararg values: Double) {
            for (v in values) response.value = v
        }
    }

    @Test
    fun `aggregate echoes a single source observation onto itself`() {
        val m = Model("AR.Echo")
        val src = TestPusher(m, "Src")
        val agg = AggregateResponse(m, "Agg")
        agg.observe(src.response)

        src.pushObservations(1.0)
        assertEquals(1.0, agg.value)
    }

    @Test
    fun `aggregate pools observations from multiple sources`() {
        val m = Model("AR.Pool")
        val src1 = TestPusher(m, "Src1")
        val src2 = TestPusher(m, "Src2")
        val agg = AggregateResponse(m, "Agg")
        agg.observe(src1.response)
        agg.observe(src2.response)

        // Drive a known pattern of observations through both sources.
        // Pool: {1.0, 0.0, 1.0, 1.0, 0.0} from src1
        //       {1.0, 1.0, 0.0}           from src2
        // Pooled mean = (1+0+1+1+0+1+1+0) / 8 = 5/8 = 0.625
        src1.pushObservations(1.0, 0.0, 1.0, 1.0, 0.0)
        src2.pushObservations(1.0, 1.0, 0.0)

        val stat = agg.withinReplicationStatistic
        assertEquals(8.0, stat.count)
        assertEquals(0.625, stat.weightedAverage, 1.0e-12)
    }

    @Test
    fun `remove detaches the source — subsequent observations are not echoed`() {
        val m = Model("AR.Remove")
        val src = TestPusher(m, "Src")
        val agg = AggregateResponse(m, "Agg")
        agg.observe(src.response)

        src.pushObservations(1.0)
        assertEquals(1.0, agg.value)

        agg.remove(src.response)
        src.pushObservations(99.0)
        // Aggregate did NOT receive the second observation.
        assertEquals(1.0, agg.value)
        assertEquals(1.0, agg.withinReplicationStatistic.count)
    }

    @Test
    fun `observeAll attaches every source in the collection`() {
        val m = Model("AR.ObserveAll")
        val srcs = (1..3).map { TestPusher(m, "Src$it") }
        val agg = AggregateResponse(m, "Agg")
        // Pass a Collection<ResponseCIfc> via the read-only views.
        agg.observeAll(srcs.map { it.response.acrossReplicationStatistic.let { _ -> it.response } })

        for (s in srcs) s.pushObservations(2.0)
        assertEquals(3.0, agg.withinReplicationStatistic.count)
        assertEquals(2.0, agg.withinReplicationStatistic.weightedAverage)
    }

    @Test
    fun `chained aggregates propagate observations to a higher-level aggregate`() {
        val m = Model("AR.Chain")
        val src = TestPusher(m, "Src")
        val midAgg = AggregateResponse(m, "MidAgg")
        val topAgg = AggregateResponse(m, "TopAgg")
        midAgg.observe(src.response)
        topAgg.observe(midAgg)

        src.pushObservations(0.5, 1.5)
        assertEquals(2.0, topAgg.withinReplicationStatistic.count)
        assertEquals(1.0, topAgg.withinReplicationStatistic.weightedAverage)
    }
}

/**
 * Regression for the [AggregateCounter.remove] CounterCIfc-overload
 * type-check bug (audit finding C): the read-only `remove(CounterCIfc)`
 * overload tested `is TWResponse` (always false for a Counter), so
 * detach via the CIfc view was a permanent no-op — leaving the
 * observer attached and double-counting across replications.  The fix
 * tests `is Counter`.
 */
class AggregateCounterRemoveTest {

    private class CounterPusher(parent: ModelElement, name: String) :
        ModelElement(parent, name) {
        val counter: Counter = Counter(this, "$name:Ctr")
    }

    @Test
    fun `remove via CounterCIfc actually detaches the source`() {
        val m = Model("AC.RemoveCIfc")
        val src = CounterPusher(m, "Src")
        val agg = AggregateCounter(m, "Agg")
        agg.observe(src.counter)
        // Detach through the read-only CounterCIfc view (the overload
        // that had the wrong type check).
        val view: CounterCIfc = src.counter
        agg.remove(view)

        // After a real detach, incrementing the source must NOT reach
        // the aggregate.
        src.counter.increment()
        src.counter.increment()
        assertEquals(0.0, agg.value,
            "aggregate must not observe a detached source; CounterCIfc " +
                "remove overload must actually detach")
    }
}
