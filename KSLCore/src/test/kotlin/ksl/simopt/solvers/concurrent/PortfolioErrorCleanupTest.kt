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
package ksl.simopt.solvers.concurrent

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 *  A portfolio run that ends with an error, because a member failed, ends every other member first: each releases
 *  its evaluator before the error is announced and before the run returns.
 */
class PortfolioErrorCleanupTest {

    private fun problem() = ProblemDefinition(
        modelIdentifier = "cleanup", objFnResponseName = "y", inputNames = listOf("x")
    ).also { it.inputVariable("x", 0.0, 1.0) }

    private fun evaluator(p: ProblemDefinition) = Evaluator(p, ResponseFunctionOracle(
        "cleanup", setOf("y"), ResponseFunctionBuilderIfc { ResponseFunctionIfc { mapOf("y" to 1.0) } }
    ))

    /** A solver whose iteration runs [action]. */
    private class ScriptSolver(p: ProblemDefinition, e: EvaluatorIfc, val action: () -> Unit) : Solver(p, e, 3, 2) {
        override fun startingPoint() = problemDefinition.midPoints()
        override fun nextPoint() = problemDefinition.midPoints()
        override fun mainIteration() = action()
    }

    /**
     *  Member 0 fails once member 1 is inside an iteration, by throwing a cancellation or, with [failRelease], by its
     *  evaluator's release throwing; member 1 finishes its iteration only when the test lets it go, after the run
     *  has failed.
     */
    private fun failedMemberWaitsForSibling(failRelease: Boolean) {
        val p = problem()
        val siblingEntered = CountDownLatch(1)
        val siblingReleased = CountDownLatch(1)
        val factory = object : MemberEvaluatorFactoryIfc {
            override fun createEvaluator(memberIndex: Int) = evaluator(p)
            override fun release(memberIndex: Int, evaluator: EvaluatorIfc, reusable: Boolean) {
                if (memberIndex == 1) siblingReleased.countDown()
                if (memberIndex == 0 && failRelease) error("release-failure")
            }
        }
        val tasks = listOf(
            SolverMemberTask(SolverFactoryIfc { e, _, _ -> ScriptSolver(p, e) {
                check(siblingEntered.await(5, TimeUnit.SECONDS))
                if (!failRelease) throw CancellationException("member-cancelled")
            } }, "failing"),
            SolverMemberTask(SolverFactoryIfc { e, _, _ -> ScriptSolver(p, e) {
                siblingEntered.countDown()
                // a slow iteration: the run's error must wait for it to end
                Thread.sleep(300)
            } }, "sibling")
        )
        val portfolio = SolverPortfolio(p, evaluator(p), tasks, factory, ConcurrentRunOptions(numWorkers = 2))
        var releasedAtError: Boolean? = null
        portfolio.lifeCycleEmitter.attach { if (it == SolverStatus.ERROR) releasedAtError = siblingReleased.count == 0L }
        assertFails { portfolio.runAllIterations() }
        assertTrue(siblingReleased.count == 0L, "the sibling had not released its evaluator when the run returned")
        assertEquals(true, releasedAtError, "the sibling had not released its evaluator when the error was announced")
    }

    @Test
    @DisplayName("a member cancelled mid-run: every other member releases its evaluator before the error is announced")
    fun cancelledMember() = failedMemberWaitsForSibling(failRelease = false)

    @Test
    @DisplayName("a member whose evaluator's release fails: every other member releases its evaluator before the error is announced")
    fun releaseFailure() = failedMemberWaitsForSibling(failRelease = true)
}
