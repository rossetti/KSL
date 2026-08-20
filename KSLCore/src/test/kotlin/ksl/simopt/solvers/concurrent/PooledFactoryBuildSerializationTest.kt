package ksl.simopt.solvers.concurrent

import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Members build their models on worker threads, so several model builders can otherwise run at
 * once. A model builder is ordinary user code and may touch state it shares with its own other
 * instances; the case that prompted this is that a free-standing random variable -- the
 * documented way to give a model its randomness -- consults the shared default stream provider.
 *
 * The provider is now thread-safe in its own right, so this is defence in depth rather than the
 * fix for that particular problem. It is worth having because it makes a study's determinism rest
 * on the harness rather than on an assumption about what an arbitrary builder does.
 */
@Timeout(60)
class PooledFactoryBuildSerializationTest {

    /** Records the greatest number of builds that were ever in flight at the same time. */
    private class ConcurrencyRecordingBuilder : ModelBuilderIfc {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val builds = AtomicInteger(0)

        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                // widen the window a builder would occupy, so overlap would be observed if allowed
                Thread.sleep(20)
                val model = Model("lkBuildProbe")
                ksl.examples.general.models.LKInventoryModel(model, "Inventory")
                model.lengthOfReplication = 120.0
                model.lengthOfReplicationWarmUp = 20.0
                model.numberOfReplications = 2
                builds.incrementAndGet()
                return model
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    @DisplayName("Concurrent members never build their models at the same time")
    fun modelBuildsDoNotOverlap() {
        val builder = ConcurrencyRecordingBuilder()
        val factory = PooledMemberEvaluatorFactory(makeLKInventoryModelProblemDefinition(), builder)
        val members = 6
        val pool = Executors.newFixedThreadPool(members)
        try {
            val tasks = (0 until members).map { k -> Callable { factory.createEvaluator(k) } }
            val evaluators = pool.invokeAll(tasks).map { it.get() }
            assertEquals(members, evaluators.size)
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
        assertTrue(builder.builds.get() > 1) {
            "The probe needs more than one build to be meaningful; got ${builder.builds.get()}"
        }
        assertEquals(1, builder.peak.get()) {
            "Model builders ran concurrently: peak in-flight was ${builder.peak.get()}"
        }
    }

    @Test
    @DisplayName("Random variables built through concurrent members keep their stream numbers")
    fun concurrentlyBuiltMembersAgreeOnStreamNumbers() {
        // The end-to-end symptom: variables that all ask for the same stream must all report it,
        // whichever thread built the model that owns them.
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map { Callable { ExponentialRV(1.0, streamNum = 1).streamNumber } }
            val numbers = pool.invokeAll(tasks).map { it.get() }
            assertTrue(numbers.all { it == 1 }) {
                "Variables that all asked for stream 1 reported $numbers"
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }
}
