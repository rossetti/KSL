package ksl.modeling.entity

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression: `seize()` injects an internal zero-time `yield()` (a `delay(0.0, …)`) purely to order seize
 * resumptions in the event calendar. That internal ordering step must NOT surface as an animation `DelayStarted`
 * /`DelayEnded` (it would otherwise show up as a bogus delay — e.g. an auto-placed storage — and as noise in any
 * delay/suspension view). A genuine modeler `delay(...)` must still emit.
 */
class YieldDelayAnimationTest {

    /** Captures every emitted animation event for inspection. */
    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    /** A minimal seize → delay("service") → release model (every seize triggers the internal yield). */
    private class TinySeizeDelay(parent: ModelElement) : ProcessModel(parent, "tiny") {
        private val server = ResourceWithQ(this, "Server")
        private val tba = RandomVariable(this, ExponentialRV(2.0, 1))
        private val st = RandomVariable(this, ExponentialRV(0.8, 2))

        private inner class Cust : Entity() {
            val work = process {
                val a = seize(server)
                delay(st, suspensionName = "service")
                release(a)
            }
        }

        override fun initialize() = schedule(::arrive, tba).let { }
        private fun arrive(e: KSLEvent<Nothing>) {
            activate(Cust().work)
            schedule(::arrive, tba)
        }
    }

    @Test
    fun `the internal seize-yield emits no Delay animation event, but a real delay still does`() {
        val m = Model("yieldTest")
        val sink = CollectingSink()
        m.animationSink = sink
        TinySeizeDelay(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        val delayStarts = sink.events.filterIsInstance<AnimationEvent.DelayStarted>()
        val names = delayStarts.mapNotNull { it.suspensionName }.distinct()
        assertTrue(delayStarts.isNotEmpty(), "the model should produce some delays")
        assertTrue(
            delayStarts.none { it.suspensionName?.contains("DELAY for YIELD") == true },
            "the internal seize-yield must not emit a Delay event; saw: $names"
        )
        assertTrue(
            delayStarts.any { it.suspensionName == "service" },
            "a real named service delay still emits; saw: $names"
        )
    }
}
