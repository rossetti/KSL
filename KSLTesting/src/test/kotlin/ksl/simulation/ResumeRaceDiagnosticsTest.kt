package ksl.simulation

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ResumeRaceDiagnosticsTest {

    @Test
    fun duplicateSameRequestResumeIsDiagnosed() {
        val model = Model("DuplicateRequestResumeProbeModel")
        DuplicateRequestResumeProbe(model)

        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0

        val failure = assertFailsWith<IllegalArgumentException> {
            model.simulate()
        }

        assertTrue(
            failure.message?.contains("Duplicate resume scheduled") == true,
            "Expected duplicate request resume diagnostic, got: ${failure.message}"
        )
        assertTrue(
            failure.message?.contains("REQUEST_Q_RESOURCE_RELEASE") == true,
            "Expected release-source diagnostic, got: ${failure.message}"
        )
    }

    @Test
    fun varyingAmountResourceRequestsDiagnoseDuplicateResume() {
        val model = Model("VaryingAmountDuplicateResumeProbeModel")
        VaryingAmountDuplicateResumeProbe(model)

        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0

        val failure = assertFailsWith<IllegalArgumentException> {
            model.simulate()
        }

        assertTrue(
            failure.message?.contains("Duplicate resume scheduled") == true,
            "Expected duplicate request resume diagnostic, got: ${failure.message}"
        )
        assertTrue(
            failure.message?.contains("resource = OvenSpace") == true,
            "Expected oven-space duplicate request resume diagnostic, got: ${failure.message}"
        )
        assertTrue(
            failure.message?.contains("amount = 175") == true,
            "Expected duplicate resume for the 175-unit request, got: ${failure.message}"
        )
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
    fun pizzaStyleConstrainedPrepStressDiagnosesDuplicateRequestResume() {
        val model = Model("ConstrainedPrepStressProbeModel")
        OvenStyleStressProbe(model, jobsPerBurst = 30, bursts = 20, prepCapacity = 8)

        model.numberOfReplications = 25
        model.lengthOfReplication = 250.0

        val failure = assertFailsWith<IllegalArgumentException> {
            model.simulate()
        }

        assertTrue(
            failure.message?.contains("Duplicate resume scheduled") == true,
            "Expected duplicate request resume diagnostic, got: ${failure.message}"
        )
        assertTrue(
            failure.message?.contains("resource = PrepWorker") == true,
            "Expected prep-worker duplicate request resume diagnostic, got: ${failure.message}"
        )
    }

    private class DuplicateRequestResumeProbe(
        parent: ModelElement
    ) : ProcessModel(parent, "DuplicateRequestResumeProbe") {

        private val sharedResource = ResourceWithQ(this, "SharedResource", capacity = 2)

        private inner class Holder : Entity() {
            val process = process("Holder") {
                val first = seize(sharedResource)
                val second = seize(sharedResource)

                delay(1.0)

                release(first)
                release(second)

                delay(1.0)
            }
        }

        private inner class WaitingJob : Entity() {
            val process = process("WaitingJob") {
                val allocation = seize(sharedResource)
                delay(0.25)
                release(allocation)
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Holder().process)
            activate(WaitingJob().process)
        }
    }

    private class VaryingAmountDuplicateResumeProbe(
        parent: ModelElement
    ) : ProcessModel(parent, "VaryingAmountDuplicateResumeProbe") {

        private val ovenSpace = ResourceWithQ(this, "OvenSpace", capacity = 500)

        private inner class Holder : Entity() {
            val process = process("Holder") {
                val first = seize(ovenSpace, 250)
                val second = seize(ovenSpace, 250)

                delay(1.0)

                release(first)
                release(second)

                delay(1.0)
            }
        }

        private inner class WaitingPizza : Entity() {
            val process = process("WaitingPizza") {
                val oven = seize(ovenSpace, 175)
                delay(0.25)
                release(oven)
            }
        }

        override fun initialize() {
            super.initialize()
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
