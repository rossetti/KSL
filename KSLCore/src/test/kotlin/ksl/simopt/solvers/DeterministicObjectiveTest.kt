package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Guards solvers against objectives that are a DETERMINISTIC function of the decision
 * variables — a legitimate and common class (a deterministic cost or staffing objective
 * subject to stochastic service constraints), and one whose sample variance is exactly
 * zero at every design point.
 *
 * The failure this reproduces is not in the solver. A solver's no-improvement check
 * compares the incumbent against itself once the search plateaus, and the default
 * equality checker for the point and population solvers
 * (`InputsAndConfidenceIntervalEquality`) then asks for a confidence interval on the
 * difference of two zero-variance estimates. The Welch-Satterthwaite degrees of freedom
 * are 0.0/0.0 = NaN there; the `dof < 1.0` clamp does not fire for NaN, and StudentT
 * rejects it — so the run dies with "The degrees of freedom must be >= 1.0" precisely
 * when the search has converged.
 *
 * The two tests are the same fixture and differ only in whether the response carries
 * noise, which isolates the cause: the noisy case is the control condition and has
 * always passed, and it is the reason the workaround of adding an artificial noise term
 * to a deterministic objective appears in several places.
 */
@Timeout(60)
class DeterministicObjectiveTest {

    private companion object {
        const val MODEL_ID = "deterministicCostFn"
        const val OBJ = "cost"
    }

    /** Minimize cost over a small integer lattice; the optimum is at (1, 1). */
    private fun makeProblem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "deterministicCostProblem",
            modelIdentifier = MODEL_ID,
            objFnResponseName = OBJ,
            inputNames = listOf("x1", "x2")
        )
        pd.inputVariable("x1", 1.0, 5.0, 1.0)
        pd.inputVariable("x2", 1.0, 5.0, 1.0)
        return pd
    }

    /**
     * @param noiseScale zero gives a response whose sample variance is exactly zero at
     * every design point; a small positive value gives the same objective in expectation
     * with nonzero variance, without disturbing the ordering on a unit lattice
     */
    private fun makeEvaluator(pd: ProblemDefinition, noiseScale: Double): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc { streamProvider ->
                val stream = streamProvider.rnStream(1)
                ResponseFunctionIfc { inputs ->
                    val cost = inputs.getValue("x1") + inputs.getValue("x2")
                    mapOf(OBJ to cost + noiseScale * (stream.randU01() - 0.5))
                }
            }
        )
        return Evaluator(pd, oracle)
    }

    /**
     * Runs a hill climber to its own termination — no replication-budget criterion, so the
     * solver's own no-improvement check decides when to stop, which is the path under test.
     */
    private fun runHillClimber(noiseScale: Double): StochasticHillClimber {
        val pd = makeProblem()
        val solver = StochasticHillClimber(
            pd, makeEvaluator(pd, noiseScale),
            maximumIterations = 100,
            replicationsPerEvaluation = 10
        )
        solver.runAllIterations()
        return solver
    }

    @Test
    @DisplayName("A hill climber completes on a deterministic (zero-variance) objective")
    fun hillClimberCompletesOnDeterministicObjective() {
        val solver = runHillClimber(noiseScale = 0.0)
        assertTrue(solver.bestSolution.isValid) {
            "The solver did not return a valid best solution: ${solver.bestSolution}"
        }
        assertTrue(solver.iterationCounter > 0) {
            "The solver did not run any iterations"
        }
    }

    @Test
    @DisplayName("Control condition: the same fixture with a noisy objective completes")
    fun hillClimberCompletesOnNoisyObjective() {
        val solver = runHillClimber(noiseScale = 1.0e-3)
        assertTrue(solver.bestSolution.isValid) {
            "The solver did not return a valid best solution: ${solver.bestSolution}"
        }
        assertTrue(solver.iterationCounter > 0) {
            "The solver did not run any iterations"
        }
    }
}
