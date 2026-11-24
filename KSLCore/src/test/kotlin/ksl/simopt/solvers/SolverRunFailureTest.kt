/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.simopt.solvers

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.Interval
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  A solver run that an exception ends is recorded as "Ended With An Error", whichever way it is run, and the reason
 *  stands: the stopping criteria are not asked again, and ending the iterations afterwards does not change it.
 */
class SolverRunFailureTest {

    private class NoEvaluations : EvaluatorIfc {
        override val totalEvaluatorCalls = 0
        override val totalDesignPointsEvaluated = 0
        override val totalOracleReplications = 0
        override val totalCachedReplications = 0
        override val cache: SolutionCacheIfc? = null
        override fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> =
            error("This solver does not request evaluations")
    }

    /** A solver whose iterations evaluate nothing and stay at the starting point; each test supplies its iteration. */
    private abstract class QuietSolver(maximum: Int) : Solver(
        problemDefinition = ProblemDefinition(
            modelIdentifier = "NoSimulation", objFnResponseName = "cost", inputNames = listOf("x")
        ).also { it.inputVariable("x", Interval(0.0, 1.0)) },
        evaluator = NoEvaluations(), maximumIterations = maximum, replicationsPerEvaluation = 2
    ) {
        override fun initializeIterations() {}
        override fun startingPoint(): InputMap = currentPoint
        override fun nextPoint(): InputMap = currentPoint
    }

    /** A solver whose iterations evaluate nothing; with [failAt] its iteration of that number throws. */
    private class StepSolver(private val failAt: Int?, maximum: Int = 3) : QuietSolver(maximum) {
        var criteriaCalls = 0
        override fun mainIteration() {
            if (iterationCounter == failAt) throw IllegalStateException("iteration $iterationCounter failed")
        }
        override fun isStoppingCriteriaSatisfied(): Boolean {
            criteriaCalls++
            return false
        }
    }

    private fun reason(solver: Solver) = (solver.solverResult as SolverResult.Completed).terminationReason

    @Test
    @DisplayName("a stepped iteration that throws ends the run with an error, without asking the criteria again")
    fun steppedFailureIsRecorded() {
        val solver = StepSolver(failAt = 2)
        solver.initialize()
        solver.runNextIteration()
        assertEquals(1, solver.criteriaCalls)
        assertFailsWith<IllegalStateException> { solver.runNextIteration() }
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
        assertEquals(1, solver.criteriaCalls)
        assertFalse(solver.iterativeProcess.isRunningStep)
        solver.endIterations("after the failure")
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
        assertEquals(1, solver.criteriaCalls)
    }

    @Test
    @DisplayName("a stepped last iteration that throws is not reported as reaching the iteration limit")
    fun steppedFailureAtTheCapIsNotTheLimit() {
        val solver = StepSolver(failAt = 1, maximum = 1)
        solver.initialize()
        assertFailsWith<IllegalStateException> { solver.runNextIteration() }
        solver.endIterations("after the failure")
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
    }

    @Test
    @DisplayName("a full run that throws ends with an error, without asking the criteria")
    fun fullRunFailureIsRecorded() {
        val solver = StepSolver(failAt = 1)
        assertFailsWith<IllegalStateException> { solver.runAllIterations() }
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
        assertEquals(0, solver.criteriaCalls)
    }

    @Test
    @DisplayName("asking for a step past the last is a misuse, not a failure of the run")
    fun stepPastTheEndIsNotAFailure() {
        val solver = StepSolver(failAt = null, maximum = 1)
        solver.initialize()
        solver.runNextIteration()
        assertFailsWith<Exception> { solver.runNextIteration() }
        solver.endIterations()
        assertEquals(SolverResult.MAXIMUM_ITERATIONS_REACHED, reason(solver))
    }

    @Test
    @DisplayName("a run without failure ends at its iteration limit, and the reason stands")
    fun normalRunKeepsItsReason() {
        val solver = StepSolver(failAt = null, maximum = 2)
        solver.runAllIterations()
        val calls = solver.criteriaCalls
        repeat(3) { assertEquals(SolverResult.MAXIMUM_ITERATIONS_REACHED, reason(solver)) }
        assertEquals(calls, solver.criteriaCalls)
    }

    @Test
    @DisplayName("a run ended by an error takes no further steps")
    fun noStepsAfterAFailure() {
        val solver = StepSolver(failAt = 2, maximum = 5)
        solver.initialize()
        solver.runNextIteration()
        assertFailsWith<IllegalStateException> { solver.runNextIteration() }
        val iterations = solver.iterationCounter
        assertFailsWith<Exception> { solver.runNextIteration() }
        assertEquals(iterations, solver.iterationCounter)
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
    }

    @Test
    @DisplayName("a full run after one ended by an error starts clean, and ends normally")
    fun rerunAfterAFailureStartsClean() {
        var fail = true
        val solver = object : QuietSolver(maximum = 3) {
            override fun mainIteration() {
                if (fail && iterationCounter == 2) throw IllegalStateException("iteration 2 failed")
            }
        }
        assertFailsWith<IllegalStateException> { solver.runAllIterations() }
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
        fail = false
        solver.runAllIterations()
        assertEquals(3, solver.iterationCounter)
        assertEquals(SolverResult.MAXIMUM_ITERATIONS_REACHED, reason(solver))
    }

    /** A solver whose iterations evaluate nothing, failing where [failIn] says, counting the error hook's calls. */
    private class HookSolver(var failIn: String?) : QuietSolver(maximum = 2) {
        var errorHookCalls = 0
        var endedNormally = 0
        override fun initializeIterations() {
            if (failIn == "initialize") throw IllegalStateException("initialization failed")
        }
        override fun mainIteration() {}
        override fun mainIterationsEnded() {
            if (failIn == "end") throw IllegalStateException("the end of the run failed")
            endedNormally++
        }
        override fun runEndedWithError() {
            errorHookCalls++
        }
    }

    @Test
    @DisplayName("an exception at the end of a run ends it with an error, and the next run starts clean")
    fun failureAtTheEndOfARun() {
        val solver = HookSolver(failIn = "end")
        assertFailsWith<IllegalStateException> { solver.runAllIterations() }
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
        assertEquals(1, solver.errorHookCalls)
        assertFailsWith<Exception> { solver.runNextIteration() }
        solver.failIn = null
        solver.runAllIterations()
        assertEquals(SolverResult.MAXIMUM_ITERATIONS_REACHED, reason(solver))
        assertEquals(1, solver.endedNormally)
        assertEquals(1, solver.errorHookCalls)
    }

    @Test
    @DisplayName("a run that fails in its initialization reports the error, and releases what it holds once")
    fun failureInInitialization() {
        val solver = HookSolver(failIn = "initialize")
        assertFailsWith<IllegalStateException> { solver.runAllIterations() }
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
        assertEquals(1, solver.errorHookCalls)
        assertEquals(0, solver.endedNormally)
    }

    @Test
    @DisplayName("runAllIterations during a stepped run is a misuse: the stepped run goes on")
    fun runAllDuringASteppedRunIsAMisuse() {
        val solver = HookSolver(failIn = null)
        solver.initialize()
        solver.runNextIteration()
        assertFailsWith<IllegalStateException> { solver.runAllIterations() }
        assertEquals(0, solver.errorHookCalls)
        solver.runNextIteration()
        solver.endIterations()
        assertEquals(SolverResult.MAXIMUM_ITERATIONS_REACHED, reason(solver))
    }

    @Test
    @DisplayName("an observer failing on a run's completion does not make the run an error")
    fun completedObserverFailureLeavesTheReason() {
        val solver = HookSolver(failIn = null)
        val statuses = mutableListOf<SolverStatus>()
        solver.lifeCycleEmitter.attach { status ->
            statuses.add(status)
            if (status == SolverStatus.COMPLETED) throw IllegalStateException("observer failed")
        }
        assertFailsWith<IllegalStateException> { solver.runAllIterations() }
        assertEquals(SolverResult.MAXIMUM_ITERATIONS_REACHED, reason(solver))
        assertFalse(SolverStatus.ERROR in statuses)
        assertEquals(0, solver.errorHookCalls)
    }

    @Test
    @DisplayName("ending a solver that never ran, when its end step fails, does not record a run")
    fun endingANeverRunSolverRecordsNoRun() {
        val solver = HookSolver(failIn = "end")
        assertFailsWith<IllegalStateException> { solver.endIterations() }
        assertTrue(solver.solverResult is SolverResult.NotExecuted)
        assertEquals(0, solver.errorHookCalls)
    }

    @Test
    @DisplayName("an observer failing on the error leaves the run ended, and the next run starts clean")
    fun errorObserverFailureStillEndsTheRun() {
        val solver = HookSolver(failIn = "end")
        solver.lifeCycleEmitter.attach { status ->
            if (status == SolverStatus.ERROR) throw IllegalArgumentException("observer failed")
        }
        val thrown = assertFailsWith<IllegalStateException> { solver.runAllIterations() }
        assertTrue(thrown.suppressed.any { it is IllegalArgumentException }, "the observer's failure is attached")
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
        assertEquals(1, solver.errorHookCalls)
        assertTrue(solver.iterativeProcess.isEnded)
    }

    @Test
    @DisplayName("an error that is not an exception ends the run as an exception does")
    fun nonExceptionErrorIsRecorded() {
        val solver = object : QuietSolver(maximum = 2) {
            override fun mainIteration() { TODO("not written yet") }
        }
        assertFailsWith<NotImplementedError> { solver.runAllIterations() }
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
        assertTrue(solver.iterativeProcess.isEnded)
    }

    @Test
    @DisplayName("a run ended by an interrupt leaves the thread interrupted")
    fun interruptIsRestored() {
        val solver = object : QuietSolver(maximum = 2) {
            // as a blocking wait does when its thread is interrupted: the flag is cleared and the exception thrown
            override fun mainIteration() { throw InterruptedException("interrupted while waiting") }
        }
        try {
            assertFailsWith<InterruptedException> { solver.runAllIterations() }
            assertEquals(SolverResult.ENDED_WITH_AN_ERROR, reason(solver))
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt is restored for the caller")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    @DisplayName("an interrupt that ends a run is restored before the run's ending, so it ends the waits there too")
    fun interruptIsRestoredBeforeTheEnding() {
        var interruptedAtEnding = false
        val solver = object : QuietSolver(maximum = 2) {
            override fun mainIteration() { throw InterruptedException("interrupted while waiting") }
            override fun runEndedWithError() { interruptedAtEnding = Thread.currentThread().isInterrupted }
        }
        try {
            assertFailsWith<InterruptedException> { solver.runAllIterations() }
            assertTrue(interruptedAtEnding, "the ending sees the interrupt")
        } finally {
            Thread.interrupted()
        }
    }
}
