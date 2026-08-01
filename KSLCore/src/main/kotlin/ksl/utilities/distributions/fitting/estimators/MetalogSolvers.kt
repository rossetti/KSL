/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.distributions.fitting.estimators

import ksl.utilities.distributions.metalog.MetalogFeasibilityChecker
import ksl.utilities.distributions.metalog.MetalogFunctions
import org.hipparchus.linear.MatrixUtils
import org.hipparchus.linear.QRDecomposition
import org.hipparchus.optim.MaxIter
import org.hipparchus.optim.linear.LinearConstraint
import org.hipparchus.optim.linear.LinearConstraintSet
import org.hipparchus.optim.linear.LinearObjectiveFunction
import org.hipparchus.optim.linear.NonNegativeConstraint
import org.hipparchus.optim.linear.Relationship
import org.hipparchus.optim.linear.SimplexSolver
import org.hipparchus.optim.nonlinear.scalar.GoalType

/**
 *  Solves the metalog least squares problem.
 *
 *  Because a metalog quantile function is linear in its coefficients, fitting one is ordinary least
 *  squares and needs no iteration. The solve goes through a QR decomposition rather than the normal
 *  equations, which squares the condition number and so loses roughly half the available precision
 *  as the number of terms grows.
 *
 *  An optional ridge term guards against the ill-conditioning that appears with many terms, at the
 *  cost of shrinking the coefficients slightly toward zero.
 *
 *  @param ridge the ridge parameter, zero for an unpenalized fit
 */
class MetalogOLSSolver(val ridge: Double = 0.0) {

    init {
        require(ridge >= 0.0) { "The ridge parameter $ridge must not be negative" }
    }

    /**
     *  Least squares coefficients for the supplied design matrix and response, or null when the
     *  system cannot be solved or the solution is not finite.
     */
    fun solveOrNull(designMatrix: Array<DoubleArray>, z: DoubleArray): DoubleArray? {
        require(designMatrix.isNotEmpty()) { "The design matrix was empty" }
        require(designMatrix.size == z.size) {
            "The design matrix has ${designMatrix.size} rows but the response has ${z.size} entries"
        }
        val numTerms = designMatrix[0].size
        val rows = if (ridge > 0.0) designMatrix.size + numTerms else designMatrix.size
        val augmented = Array(rows) { DoubleArray(numTerms) }
        val response = DoubleArray(rows)
        for (i in designMatrix.indices) {
            designMatrix[i].copyInto(augmented[i])
            response[i] = z[i]
        }
        if (ridge > 0.0) {
            // Appending a scaled identity block is algebraically the same as adding the ridge
            // penalty, and keeps the whole solve inside one QR decomposition.
            val root = kotlin.math.sqrt(ridge)
            for (j in 0 until numTerms) {
                augmented[designMatrix.size + j][j] = root
            }
        }
        return try {
            val solution = QRDecomposition(MatrixUtils.createRealMatrix(augmented))
                .solver
                .solve(MatrixUtils.createRealVector(response))
                .toArray()
            if (solution.all { it.isFinite() }) solution else null
        } catch (e: RuntimeException) {
            null
        }
    }
}

/**
 *  Fits metalog coefficients as a linear program, minimizing the sum of absolute deviations subject
 *  to explicit monotonicity constraints.
 *
 *  This exists because least squares can produce coefficients whose quantile function is not
 *  strictly increasing, which is not a distribution at all. Constraining the derivative directly
 *  makes the solution valid by construction, so a term count that least squares cannot fit becomes
 *  usable rather than being discarded.
 *
 *  Two details matter for correctness. The constraints are imposed on the same probability grid the
 *  feasibility checker scans, because a coarser constraint grid lets the solution satisfy every
 *  constraint while still failing between grid points; measured on a case least squares could not
 *  fit, a grid ten times coarser produced a solution the checker rejected. And the floor on the
 *  derivative is expressed relative to the spread of the response rather than as a fixed number,
 *  because the derivative carries the units of the data: a fixed floor is vacuous on large-scale
 *  data and badly distorting on small-scale data. The solve is then verified and the floor raised
 *  until the checker agrees, which in testing never needed more than two attempts.
 *
 *  Coefficients are unrestricted in sign but the simplex solver requires non-negative variables, so
 *  each coefficient is carried as a difference of two non-negative variables, as are the residuals.
 *
 *  @param initialRelativeFloor the first derivative floor, as a fraction of the response spread
 *  @param maxEscalations how many times the floor may be raised before giving up
 *  @param maxIterations the iteration cap handed to the simplex solver
 */
class MetalogLPSolver(
    val initialRelativeFloor: Double = DEFAULT_INITIAL_RELATIVE_FLOOR,
    val maxEscalations: Int = DEFAULT_MAX_ESCALATIONS,
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    val constraintStep: Double = DEFAULT_CONSTRAINT_STEP,
    val constraintTailDecades: Int = DEFAULT_CONSTRAINT_TAIL_DECADES
) {



    init {
        require(initialRelativeFloor > 0.0) {
            "The initial relative floor $initialRelativeFloor must be positive"
        }
        require(maxEscalations >= 1) { "The escalation limit $maxEscalations must be at least 1" }
        require(maxIterations >= 1) { "The iteration limit $maxIterations must be at least 1" }
    }

    /**
     *  How many times the floor had to be raised on the most recent successful solve, which is one
     *  when no escalation was needed. Useful for diagnosing a fit that only just converged.
     */
    var escalationsUsed: Int = 0
        private set

    /**
     *  Coefficients that fit the response as closely as the absolute-deviation objective allows
     *  while defining a strictly increasing quantile function, or null when no such fit was found.
     *
     *  @param probabilities the cumulative probabilities of the data points
     *  @param z the response, already mapped into the metalog's fitting space
     *  @param numTerms how many metalog terms to fit
     *  @param checker the feasibility checker whose grid the constraints are built on and whose
     *  verdict the solution must satisfy
     */
    fun solveOrNull(
        probabilities: DoubleArray,
        z: DoubleArray,
        numTerms: Int,
        checker: MetalogFeasibilityChecker = MetalogFeasibilityChecker.defaultChecker
    ): DoubleArray? {
        require(probabilities.size == z.size) {
            "There are ${probabilities.size} probabilities but ${z.size} responses"
        }
        require(numTerms >= MetalogFunctions.MIN_TERMS) {
            "The number of terms $numTerms must be at least ${MetalogFunctions.MIN_TERMS}"
        }
        if (z.any { !it.isFinite() }) {
            return null
        }
        // The program is solved on a standardized response rather than the raw one. A simplex
        // works with absolute pivot tolerances, so a response spanning millions makes those
        // tolerances relatively meaningless and the solve fails outright. Standardizing also makes
        // the derivative floor unitless, since the standardized spread is one by construction.
        val shift = z.min()
        val spread = (z.max() - shift).let { if (it > 0.0) it else 1.0 }
        val standardized = DoubleArray(z.size) { (z[it] - shift) / spread }
        val design = MetalogFunctions.designMatrix(probabilities, numTerms)
        val attempts = minOf(maxEscalations, ESCALATION_SCHEDULE.size)
        for (attempt in 0 until attempts) {
            val (stepDivisor, floorMultiplier) = ESCALATION_SCHEDULE[attempt]
            val derivatives = derivativeMatrix(constraintGridFor(stepDivisor), numTerms)
            val floor = initialRelativeFloor * floorMultiplier
            val standardCoefficients = solveOnce(design, standardized, derivatives, floor, numTerms)
            if (standardCoefficients != null) {
                val candidate = destandardize(standardCoefficients, shift, spread)
                if (checker.isFeasible(candidate)) {
                    escalationsUsed = attempt + 1
                    return candidate
                }
            }
        }
        return null
    }

    /**
     *  The probability grid the monotonicity constraints are imposed on, at a given refinement.
     *  Refinement stops at the verification resolution, past which extra constraints buy nothing.
     */
    private fun constraintGridFor(stepDivisor: Double): DoubleArray {
        val step = (constraintStep / stepDivisor)
            .coerceAtLeast(MetalogFeasibilityChecker.DEFAULT_UNIFORM_STEP)
        return MetalogFeasibilityChecker(step, constraintTailDecades).grid()
    }

    /**
     *  Maps coefficients fitted to a standardized response back to the original scale. The quantile
     *  function is a linear combination whose first basis term is the constant one, so the shift is
     *  absorbed entirely by the first coefficient and every coefficient scales by the spread.
     */
    private fun destandardize(
        standardCoefficients: DoubleArray,
        shift: Double,
        spread: Double
    ): DoubleArray {
        val coefficients = DoubleArray(standardCoefficients.size) { spread * standardCoefficients[it] }
        coefficients[0] += shift
        return coefficients
    }

    /**
     *  One simplex solve at a fixed derivative floor.
     */
    private fun solveOnce(
        design: Array<DoubleArray>,
        z: DoubleArray,
        derivatives: Array<DoubleArray>,
        floor: Double,
        numTerms: Int
    ): DoubleArray? {
        val numPoints = z.size
        // Variables run: positive residuals, negative residuals, positive coefficients, negative
        // coefficients. The objective charges only the residuals.
        val numVariables = 2 * numPoints + 2 * numTerms
        val cost = DoubleArray(numVariables)
        for (i in 0 until 2 * numPoints) {
            cost[i] = 1.0
        }
        val constraints = ArrayList<LinearConstraint>(numPoints + derivatives.size)
        for (i in 0 until numPoints) {
            val row = DoubleArray(numVariables)
            row[i] = 1.0
            row[numPoints + i] = -1.0
            for (j in 0 until numTerms) {
                row[2 * numPoints + j] = design[i][j]
                row[2 * numPoints + numTerms + j] = -design[i][j]
            }
            constraints.add(LinearConstraint(row, Relationship.EQ, z[i]))
        }
        for (derivativeRow in derivatives) {
            val row = DoubleArray(numVariables)
            for (j in 0 until numTerms) {
                row[2 * numPoints + j] = derivativeRow[j]
                row[2 * numPoints + numTerms + j] = -derivativeRow[j]
            }
            constraints.add(LinearConstraint(row, Relationship.GEQ, floor))
        }
        return try {
            val solution = SimplexSolver().optimize(
                MaxIter(maxIterations),
                LinearObjectiveFunction(cost, 0.0),
                LinearConstraintSet(constraints),
                GoalType.MINIMIZE,
                NonNegativeConstraint(true)
            )
            val point = solution.point
            val coefficients = DoubleArray(numTerms) {
                point[2 * numPoints + it] - point[2 * numPoints + numTerms + it]
            }
            if (coefficients.all { it.isFinite() }) coefficients else null
        } catch (e: RuntimeException) {
            // The simplex reports an infeasible or unbounded program, or exhausted iterations, by
            // throwing. Any of those means this floor produced no usable fit.
            null
        }
    }

    /**
     *  One row per grid probability, holding the derivative of each basis term there. The
     *  monotonicity constraint is that this matrix times the coefficients stays above the floor.
     */
    private fun derivativeMatrix(grid: DoubleArray, numTerms: Int): Array<DoubleArray> {
        return Array(grid.size) { row ->
            DoubleArray(numTerms) { column ->
                MetalogFunctions.basisTermDerivative(column + 1, grid[row])
            }
        }
    }

    companion object {

        /**
         *  The first derivative floor tried, as a fraction of the spread of the response. Small
         *  enough not to distort a fit that was nearly valid already.
         */
        const val DEFAULT_INITIAL_RELATIVE_FLOOR: Double = 1.0E-6

        /**
         *  How many times the floor may be raised. Two attempts sufficed in every case tested; the
         *  remainder is margin.
         */
        const val DEFAULT_MAX_ESCALATIONS: Int = 6

        /**
         *  The iteration cap handed to the simplex solver.
         */
        const val DEFAULT_MAX_ITERATIONS: Int = 5000

        /**
         *  The order in which constraint refinement and floor raising are tried, as a divisor on
         *  the constraint spacing paired with a multiplier on the derivative floor.
         *
         *  The two are escalated separately rather than together, because they address different
         *  failures and pull against each other. Verification can fail for want of margin between
         *  constraint points, which a higher floor fixes, or because the quantile function wiggles
         *  between them, which only more constraints fix. Raising the floor on an already fine grid
         *  tends to make the program infeasible outright, especially with few terms, where the
         *  derivative has little freedom to stay above a large floor everywhere at once. So the
         *  cheap remedy is tried first, then refinement at the original floor.
         */
        val ESCALATION_SCHEDULE: List<Pair<Double, Double>> = listOf(
            Pair(1.0, 1.0),
            Pair(1.0, 100.0),
            Pair(2.0, 1.0),
            Pair(5.0, 1.0),
            Pair(10.0, 1.0),
            Pair(10.0, 100.0)
        )

        /**
         *  The interior spacing of the grid the monotonicity constraints are imposed on. Coarser
         *  than the verification grid on purpose; see the class documentation.
         */
        const val DEFAULT_CONSTRAINT_STEP: Double = 0.01

        /**
         *  How many powers of ten of tail refinement the constraint grid carries.
         *
         *  This matches the verification grid rather than being coarsened with the interior. The
         *  interior of a metalog quantile function is smooth, so constraints there can be sparse,
         *  but the tails are where a derivative sign change hides, and a constraint grid that stops
         *  short of the verification depth leaves the solver blind to exactly the region the
         *  checker will look at. Tail points cost two per decade, so matching is nearly free.
         */
        const val DEFAULT_CONSTRAINT_TAIL_DECADES: Int =
            MetalogFeasibilityChecker.DEFAULT_TAIL_DECADES
    }
}
