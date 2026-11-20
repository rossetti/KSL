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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A solver's report says why the run ended: a run that used all its iterations was not halted or in error. */
class SolverTerminationReportTest {

    @Test
    @DisplayName("a run that reaches its maximum iterations reports that, not a halt or an error")
    fun maximumIterationsReported() {
        val pd = ProblemDefinition(
            problemName = "reportProblem", modelIdentifier = "fn", objFnResponseName = "cost",
            inputNames = listOf("x1", "x2")
        )
        pd.inputVariable("x1", 1.0, 50.0, 1.0)
        pd.inputVariable("x2", 1.0, 50.0, 1.0)
        val oracle = ResponseFunctionOracle("fn", setOf("cost"), ResponseFunctionBuilderIfc { provider ->
            val stream = provider.rnStream(1)
            ResponseFunctionIfc { inputs -> mapOf("cost" to inputs.getValue("x1") + inputs.getValue("x2") + stream.randU01()) }
        })
        val solver = StochasticHillClimber(pd, Evaluator(pd, oracle), maximumIterations = 2, replicationsPerEvaluation = 5)
        solver.runAllIterations()
        val result = solver.solverResult as SolverResult.Completed
        assertEquals(2, result.totalIterations, "the run used its iterations")
        assertFalse(result.isStoppingCriteriaMet, "the stopping criteria were not what ended it")
        val report = result.toReportString()
        assertTrue("Maximum Iterations Reached" in report, report)
        assertFalse("Halted" in report, report)
    }
}
