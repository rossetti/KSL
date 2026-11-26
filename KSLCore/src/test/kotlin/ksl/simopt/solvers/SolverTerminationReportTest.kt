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

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.solverResult
import ksl.utilities.io.report.toMarkdown
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A solver's report says why the run ended: a run that used all its iterations was not halted or in error. */
class SolverTerminationReportTest {

    private fun problem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "reportProblem", modelIdentifier = "fn", objFnResponseName = "cost",
            inputNames = listOf("x1", "x2")
        )
        pd.inputVariable("x1", 1.0, 50.0, 1.0)
        pd.inputVariable("x2", 1.0, 50.0, 1.0)
        return pd
    }

    /** A hill climber on a noisy linear cost; [onEvaluation] runs at each evaluation the oracle makes. */
    private fun climber(iterations: Int, onEvaluation: () -> Unit = {}): StochasticHillClimber {
        val pd = problem()
        val oracle = ResponseFunctionOracle("fn", setOf("cost"), ResponseFunctionBuilderIfc { provider ->
            val stream = provider.rnStream(1)
            ResponseFunctionIfc { inputs ->
                onEvaluation()
                mapOf("cost" to inputs.getValue("x1") + inputs.getValue("x2") + stream.randU01())
            }
        })
        return StochasticHillClimber(pd, Evaluator(pd, oracle), maximumIterations = iterations, replicationsPerEvaluation = 5)
    }

    private fun SolverResult.Completed.rendered(): String = report("Solver") { solverResult(this@rendered) }.toMarkdown()

    @Test
    @DisplayName("a run that reaches its maximum iterations reports that, not a halt or an error, in both reports")
    fun maximumIterationsReported() {
        val solver = climber(2)
        solver.runAllIterations()
        val result = solver.solverResult as SolverResult.Completed
        assertEquals(2, result.totalIterations, "the run used its iterations")
        assertFalse(result.isStoppingCriteriaMet, "the stopping criteria were not what ended it")
        for (text in listOf(result.toReportString(), result.rendered())) {
            assertTrue(text.contains("Maximum Iterations Reached", ignoreCase = true), text)
            assertFalse(text.contains("Halted", ignoreCase = true), text)
            assertFalse(text.contains("error", ignoreCase = true), text)
        }
    }

    @Test
    @DisplayName("the reason a run ended is fixed when it ends: raising the limit afterwards does not make it a halt")
    fun reasonFixedWhenTheRunEnds() {
        val solver = climber(2)
        solver.runAllIterations()
        solver.maximumNumberIterations = 10
        val result = solver.solverResult as SolverResult.Completed
        for (text in listOf(result.toReportString(), result.rendered())) {
            assertTrue(text.contains("Maximum Iterations Reached", ignoreCase = true), text)
        }
    }

    @Test
    @DisplayName("a run halted during its last iteration reports a halt, not its maximum iterations reached")
    fun haltInTheLastIteration() {
        lateinit var solver: StochasticHillClimber
        solver = climber(2) { if (solver.iterationCounter == 2) solver.stopIterations("halted by the test") }
        solver.runAllIterations()
        val result = solver.solverResult as SolverResult.Completed
        assertEquals(2, result.totalIterations)
        for (text in listOf(result.toReportString(), result.rendered())) {
            assertTrue(text.contains("Halted Before Completion", ignoreCase = true), text)
            assertFalse(text.contains("Maximum Iterations Reached", ignoreCase = true), text)
        }
    }

    @Test
    @DisplayName("a stepped run that uses every iteration and is then ended reports its maximum iterations, not a halt")
    fun steppedRunUsingEveryIteration() {
        val solver = climber(3)
        solver.initialize()
        while (solver.hasNextIteration()) solver.runNextIteration()
        solver.endIterations()
        val result = solver.solverResult as SolverResult.Completed
        assertEquals(3, result.totalIterations)
        assertEquals(SolverResult.MAXIMUM_ITERATIONS_REACHED, result.terminationReason)
    }

    @Test
    @DisplayName("a stepped run that uses every iteration and is then stopped and ended reports its maximum iterations, not a halt")
    fun steppedRunStoppedAfterEveryIteration() {
        val solver = climber(1)
        solver.initialize()
        while (solver.hasNextIteration()) solver.runNextIteration()
        solver.stopIterations("stopped once the steps were done")
        solver.endIterations()
        val result = solver.solverResult as SolverResult.Completed
        assertEquals(1, result.totalIterations)
        assertEquals(SolverResult.MAXIMUM_ITERATIONS_REACHED, result.terminationReason)
    }

    @Test
    @DisplayName("a stepped run stopped before its last iteration and then ended is a halt")
    fun steppedRunStoppedEarly() {
        val solver = climber(3)
        solver.initialize()
        solver.runNextIteration()
        solver.stopIterations("stopped early")
        solver.endIterations()
        val result = solver.solverResult as SolverResult.Completed
        assertEquals(SolverResult.HALTED_BEFORE_COMPLETION, result.terminationReason)
    }

    @Test
    @DisplayName("a stepped run ended before its last iteration is a halt")
    fun steppedRunEndedEarly() {
        val solver = climber(3)
        solver.initialize()
        solver.runNextIteration()
        solver.endIterations()
        val result = solver.solverResult as SolverResult.Completed
        assertEquals(SolverResult.HALTED_BEFORE_COMPLETION, result.terminationReason)
    }

    @Test
    @DisplayName("a run ended by an exception reports that it ended with an error")
    fun runEndedByAnException() {
        var armed = true
        val solver = climber(5)
        solver.solutionQualityEvaluator = SolutionQualityEvaluatorIfc { s ->
            if (armed && s.iterationCounter == 2) {
                armed = false
                throw IllegalStateException("criterion broke")
            }
            false
        }
        val thrown = runCatching { solver.runAllIterations() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "the exception reaches the caller")
        val result = solver.solverResult as SolverResult.Completed
        assertEquals(SolverResult.ENDED_WITH_AN_ERROR, result.terminationReason)
        assertTrue(result.toReportString().contains("Ended With An Error"), result.toReportString())
    }

    @Test
    @DisplayName("a result read after the run is a snapshot: the criteria are not asked again")
    fun resultAfterTheRunIsASnapshot() {
        var asked = 0
        val solver = climber(3)
        solver.solutionQualityEvaluator = SolutionQualityEvaluatorIfc { _ -> asked++; false }
        solver.runAllIterations()
        val first = solver.solverResult as SolverResult.Completed
        val askedAfterRun = asked
        val second = solver.solverResult as SolverResult.Completed
        assertEquals(askedAfterRun, asked, "reading the result asks the criteria nothing")
        assertEquals(first.isStoppingCriteriaMet, second.isStoppingCriteriaMet)
        assertEquals(first.terminationReason, second.terminationReason)
    }
}
