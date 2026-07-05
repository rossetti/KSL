package ksl.examples.general.simopt.study1

import ksl.examples.general.simopt.problems.ConstrainedNoisyQuadratic
import ksl.examples.general.simopt.problems.MultiItemNewsvendor
import ksl.examples.general.simopt.problems.Newsvendor
import ksl.examples.general.simopt.problems.NoiseLevel
import ksl.examples.general.simopt.problems.NoisyRastrigin
import ksl.examples.general.simopt.problems.NoisyRosenbrock
import ksl.examples.general.simopt.problems.NoisySphere
import ksl.examples.general.simopt.problems.SyntheticFunctionProblem

/**
 *  The exact, estimation-free optimality gap at a recorded recommendation — the toy-
 *  problem superpower. Because every Study-1 problem has a known optimum and a closed-form
 *  (noise-free) objective, the true gap of a run is computed by evaluating that objective
 *  at the run's stored best inputs and comparing to the known optimum, with no simulation
 *  noise. Reported alongside the recorded (estimated) gap, it exposes the estimated-vs-true
 *  bias that motivates the confirmation stage.
 *
 *  Gaps are oriented so that 0 = the optimum and larger = worse, for both minimization and
 *  maximization problems.
 */
object Study1TrueGap {

    private class Entry(
        val inputNames: List<String>,
        val trueObjective: (DoubleArray) -> Double,
        val optimumValue: Double,
        val maximize: Boolean,
        // the NOISE-FREE constraint predicate; null for unconstrained problems (always feasible)
        val trueFeasible: ((DoubleArray) -> Boolean)? = null
    ) {
        private fun point(inputs: Map<String, Double>): DoubleArray =
            DoubleArray(inputNames.size) { inputs.getValue(inputNames[it]) }

        fun gap(inputs: Map<String, Double>): Double {
            val value = trueObjective(point(inputs))
            return if (maximize) optimumValue - value else value - optimumValue
        }

        fun feasible(inputs: Map<String, Double>): Boolean =
            trueFeasible?.invoke(point(inputs)) ?: true
    }

    private fun syntheticEntry(problem: SyntheticFunctionProblem): Pair<String, Entry> {
        return problem.problemName to Entry(
            inputNames = problem.inputNames,
            trueObjective = { problem.trueObjective(it) },
            optimumValue = problem.trueObjective(problem.optimum),
            maximize = false
        )
    }

    private val entries: Map<String, Entry> = buildMap {
        // Tier A: unconstrained additive-noise families
        for (d in listOf(2, 5)) {
            for (nl in NoiseLevel.entries) {
                this += syntheticEntry(NoisySphere(d, nl))
                this += syntheticEntry(NoisyRosenbrock(d, nl))
                this += syntheticEntry(NoisyRastrigin(d, nl))
            }
            // constrained quadratic: noise-free constraint is the coordinate sum <= 3d
            for (nl in listOf(NoiseLevel.LOW, NoiseLevel.MED)) {
                val q = ConstrainedNoisyQuadratic(d, nl)
                val budget = 3.0 * d
                this += q.problemName to Entry(
                    inputNames = q.inputNames,
                    trueObjective = { q.trueObjective(it) },
                    optimumValue = q.trueObjective(q.optimum),
                    maximize = false,
                    trueFeasible = { it.sum() <= budget }
                )
            }
        }
        // Newsvendors (maximization, closed-form expected profit)
        val newsvendor = Newsvendor()
        this += newsvendor.problemName to Entry(
            inputNames = listOf(newsvendor.inputName),
            trueObjective = { newsvendor.expectedProfit(it[0]) },
            optimumValue = newsvendor.expectedProfit(newsvendor.optimalOrderQuantity),
            maximize = true
        )
        val multi = MultiItemNewsvendor()
        this += multi.problemName to Entry(
            inputNames = multi.inputNames,
            trueObjective = { multi.expectedProfit(it) },
            optimumValue = multi.expectedProfit(multi.optimalOrderQuantities),
            maximize = true,
            trueFeasible = { it.sum() <= multi.unitBudget.toDouble() }
        )
    }

    /** The known optimum (true objective) value for a problem, or null if unknown. */
    fun knownOptimum(problemName: String): Double? = entries[problemName]?.optimumValue

    /**
     *  The exact optimality gap at the recommendation, or null if the problem has no
     *  registered true objective or the inputs are missing a variable (e.g. a failed
     *  cell's sentinel).
     */
    fun trueGap(problemName: String, bestInputs: Map<String, Double>): Double? {
        val entry = entries[problemName] ?: return null
        return try {
            entry.gap(bestInputs)
        } catch (e: NoSuchElementException) {
            null
        }
    }

    /**
     *  Whether the recommendation is TRULY feasible (evaluated against the noise-free
     *  constraint), or null if the problem is unregistered or the inputs are incomplete.
     *  Unconstrained problems always return true.
     */
    fun trueFeasible(problemName: String, bestInputs: Map<String, Double>): Boolean? {
        val entry = entries[problemName] ?: return null
        return try {
            entry.feasible(bestInputs)
        } catch (e: NoSuchElementException) {
            null
        }
    }
}
