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

    /** A solver whose iterations evaluate nothing; with [failAt] its iteration of that number throws. */
    private class StepSolver(private val failAt: Int?, maximum: Int = 3) : Solver(
        problemDefinition = ProblemDefinition(
            modelIdentifier = "NoSimulation", objFnResponseName = "cost", inputNames = listOf("x")
        ).also { it.inputVariable("x", Interval(0.0, 1.0)) },
        evaluator = NoEvaluations(), maximumIterations = maximum, replicationsPerEvaluation = 2
    ) {
        var criteriaCalls = 0
        override fun initializeIterations() {}
        override fun startingPoint(): InputMap = currentPoint
        override fun nextPoint(): InputMap = currentPoint
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
}
