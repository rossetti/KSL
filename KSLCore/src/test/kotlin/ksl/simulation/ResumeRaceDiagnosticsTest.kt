package ksl.simulation

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResumeRaceDiagnosticsTest {

    @Test
    fun duplicateSameRequestResumeIsPrevented() {
        val model = Model("DuplicateRequestResumeProbeModel")
        val probe = DuplicateRequestResumeProbe(model)

        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0

        assertDoesNotThrow {
            model.simulate()
        }
        assertEquals(2, probe.completedCount)
    }

    @Test
    fun varyingAmountResourceRequestsDoNotDuplicateResume() {
        val model = Model("VaryingAmountDuplicateResumeProbeModel")
        val probe = VaryingAmountDuplicateResumeProbe(model)

        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0

        assertDoesNotThrow {
            model.simulate()
        }
        assertEquals(2, probe.completedCount)
    }

    @Test
    fun normalSeizeDelayReleaseDelayPatternDoesNotTripDiagnostics() {
        val model = Model("NormalSDRProbe")
        NormalSeizeDelayReleaseProbe(model, numJobs = 25)

        model.numberOfReplications = 10
        model.lengthOfReplication = 100.0

        assertDoesNotThrow {
            model.simulate()
        }
    }

    @Test
    fun ovenStyleMultiUnitStressDoesNotTripDiagnostics() {
        val model = Model("OvenStyleStressProbeModel")
        OvenStyleStressProbe(model, jobsPerBurst = 30, bursts = 20)

        model.numberOfReplications = 25
        model.lengthOfReplication = 250.0

        assertDoesNotThrow {
            model.simulate()
        }
    }

    @Test
    fun pizzaStyleConstrainedPrepStressDoesNotDuplicateRequestResume() {
        val model = Model("ConstrainedPrepStressProbeModel")
        OvenStyleStressProbe(model, jobsPerBurst = 30, bursts = 20, prepCapacity = 8)

        model.numberOfReplications = 25
        model.lengthOfReplication = 250.0

        assertDoesNotThrow {
            model.simulate()
        }
    }

    private class DuplicateRequestResumeProbe(
        parent: ModelElement
    ) : ProcessModel(parent, "DuplicateRequestResumeProbe") {

        private val sharedResource = ResourceWithQ(this, "SharedResource", capacity = 2)
        var completedCount = 0
            private set

        private inner class Holder : Entity() {
            val process = process("Holder") {
                val first = seize(sharedResource)
                val second = seize(sharedResource)

                delay(1.0)

                release(first)
                release(second)

                delay(1.0)
                completedCount++
            }
        }

        private inner class WaitingJob : Entity() {
            val process = process("WaitingJob") {
                val allocation = seize(sharedResource)
                delay(0.25)
                release(allocation)
                completedCount++
            }
        }

        override fun initialize() {
            super.initialize()
            completedCount = 0
            activate(Holder().process)
            activate(WaitingJob().process)
        }
    }

    private class VaryingAmountDuplicateResumeProbe(
        parent: ModelElement
    ) : ProcessModel(parent, "VaryingAmountDuplicateResumeProbe") {

        private val ovenSpace = ResourceWithQ(this, "OvenSpace", capacity = 500)
        var completedCount = 0
            private set

        private inner class Holder : Entity() {
            val process = process("Holder") {
                val first = seize(ovenSpace, 250)
                val second = seize(ovenSpace, 250)

                delay(1.0)

                release(first)
                release(second)

                delay(1.0)
                completedCount++
            }
        }

        private inner class WaitingPizza : Entity() {
            val process = process("WaitingPizza") {
                val oven = seize(ovenSpace, 175)
                delay(0.25)
                release(oven)
                completedCount++
            }
        }

        override fun initialize() {
            super.initialize()
            completedCount = 0
            activate(Holder().process)
            activate(WaitingPizza().process)
        }
    }

    private class NormalSeizeDelayReleaseProbe(
        parent: ModelElement,
        private val numJobs: Int
    ) : ProcessModel(parent, "NormalSeizeDelayReleaseProbe") {

        private val worker = ResourceWithQ(this, "Worker", capacity = 1)
        private val completed = Counter(this, "Completed")

        private inner class Job(private val index: Int) : Entity() {
            val process = process("Job") {
                val allocation = seize(worker)
                delay(0.5 + (index % 3) * 0.1)
                release(allocation)
                delay(0.25)
                completed.increment()
            }
        }

        override fun initialize() {
            super.initialize()
            repeat(numJobs) { index ->
                activate(Job(index).process, timeUntilActivation = index * 0.05)
            }
        }
    }

    private class OvenStyleStressProbe(
        parent: ModelElement,
        private val jobsPerBurst: Int,
        private val bursts: Int,
        private val prepCapacity: Int = jobsPerBurst * bursts
    ) : ProcessModel(parent, "OvenStyleStressProbe") {

        private val prepWorker = ResourceWithQ(this, "PrepWorker", capacity = prepCapacity)
        private val ovenSpace = ResourceWithQ(this, "OvenSpace", capacity = 500)
        private val completed = Counter(this, "Completed")
        private val sizes = intArrayOf(115, 175, 250)

        private inner class Pizza(private val index: Int) : Entity() {
            val process = process("Pizza") {
                val prep = seize(prepWorker)
                delay((index % 4) * 0.05)

                val needed = sizes[index % sizes.size]
                val oven = seize(ovenSpace, needed)
                release(prep)

                delay(1.9)
                release(oven)

                delay(7.5)
                completed.increment()
            }
        }

        override fun initialize() {
            super.initialize()
            repeat(bursts) { burst ->
                repeat(jobsPerBurst) { offset ->
                    val index = burst * jobsPerBurst + offset
                    activate(Pizza(index).process, timeUntilActivation = burst * 0.2)
                }
            }
        }
    }
}
