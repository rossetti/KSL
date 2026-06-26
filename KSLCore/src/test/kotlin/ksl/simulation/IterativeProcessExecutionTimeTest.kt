package ksl.simulation

import ksl.simulation.IterativeProcessIfc.EndingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Enforcement test for the per-process wall-clock cap
 * ([IterativeProcessIfc.maximumAllowedExecutionTime]).
 *
 * The cap is the substrate primitive behind the model's
 * `maximumAllowedExecutionTimePerReplication`: between steps (events), the
 * run loop compares elapsed wall-clock time against the cap and ends with
 * [EndingStatus.EXCEEDED_EXECUTION_TIME] when it trips. A regression once made
 * the trip condition circular (it read back the very outcome it was meant to
 * produce), so the cap never fired and a runaway process ran unbounded. This
 * test pins the live behavior.
 */
class IterativeProcessExecutionTimeTest {

    /** A never-ending iterative process; each step sleeps briefly so wall-clock
     *  accrues without a hot spin. Only the time cap can stop it. */
    private class NeverEndingProcess : IterativeProcess<Int>() {
        override fun hasNextStep(): Boolean = true
        override fun nextStep(): Int = 1
        override fun runStep() {
            myCurrentStep = nextStep()
            Thread.sleep(5)
        }
    }

    @Test
    fun `the wall-clock cap ends a runaway process with EXCEEDED_EXECUTION_TIME`() {
        val process = NeverEndingProcess()
        process.maximumAllowedExecutionTime = 300.milliseconds

        // Run on a separate thread so a regression (infinite loop) fails by
        // join-timeout instead of hanging the suite forever.
        val runner = Thread { process.run() }
        runner.isDaemon = true
        runner.start()
        runner.join(15.seconds.inWholeMilliseconds)

        if (runner.isAlive) {
            runner.interrupt()
            throw AssertionError("the cap never fired: the process ran past 15s with a 300ms cap")
        }

        assertEquals(
            EndingStatus.EXCEEDED_EXECUTION_TIME,
            process.endingStatus,
            "expected the run to end by exceeding its execution-time cap",
        )
        assertTrue(process.isExecutionTimeExceeded, "isExecutionTimeExceeded should read back the recorded outcome")
        assertTrue(process.numberStepsCompleted >= 1, "at least one step should have run before the cap tripped")
    }

    @Test
    fun `a process with no cap is not flagged as time-exceeded`() {
        // A short, finite process never sets the cap; it should complete normally.
        val process = object : IterativeProcess<Int>() {
            private var remaining = 3
            override fun hasNextStep(): Boolean = remaining > 0
            override fun nextStep(): Int = remaining
            override fun runStep() {
                myCurrentStep = nextStep()
                remaining--
            }
        }
        process.run()
        assertEquals(EndingStatus.COMPLETED_ALL_STEPS, process.endingStatus)
        assertTrue(!process.isExecutionTimeExceeded)
    }
}
