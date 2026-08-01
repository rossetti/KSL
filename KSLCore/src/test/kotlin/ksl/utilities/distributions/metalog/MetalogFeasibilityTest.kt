package ksl.utilities.distributions.metalog

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetalogFeasibilityTest {

    private val checker = MetalogFeasibilityChecker()

    private fun assertNearly(
        expected: Double,
        actual: Double,
        relTol: Double = 1e-9,
        absTol: Double = 1e-9,
        message: String = "",
    ) {
        val diff = abs(expected - actual)
        val threshold = max(absTol, relTol * max(1.0, abs(expected)))
        assertTrue(
            diff <= threshold,
            "$message Expected $expected, actual $actual, diff $diff, threshold $threshold",
        )
    }

    /**
     *  Scans the derivative directly on a far denser grid than the production checker uses, so
     *  that the production grid can be held to an independent standard.
     */
    private fun feasibleByDenseScan(coefficients: DoubleArray): Boolean {
        val points = 200_001
        for (i in 0 until points) {
            val y = 1.0e-11 + (1.0 - 2.0e-11) * i / (points - 1).toDouble()
            if (MetalogFunctions.quantileDerivative(coefficients, y) <= 0.0) {
                return false
            }
        }
        for (decade in 2..11) {
            for (factor in intArrayOf(1, 2, 3, 5, 7)) {
                val tail = factor * Math.pow(10.0, -decade.toDouble())
                if (tail <= 0.0 || tail >= 0.5) continue
                if (MetalogFunctions.quantileDerivative(coefficients, tail) <= 0.0) return false
                if (MetalogFunctions.quantileDerivative(coefficients, 1.0 - tail) <= 0.0) return false
            }
        }
        return true
    }

    // -------- exact two-term condition --------

    @Test
    fun twoTermIsFeasibleExactlyWhenTheScaleIsPositive() {
        assertTrue(MetalogFeasibilityChecker.isFeasible2Term(1.0))
        assertTrue(MetalogFeasibilityChecker.isFeasible2Term(1.0e-9))
        assertFalse(MetalogFeasibilityChecker.isFeasible2Term(0.0))
        assertFalse(MetalogFeasibilityChecker.isFeasible2Term(-1.0e-9))
        assertFalse(MetalogFeasibilityChecker.isFeasible2Term(-3.0))
        assertFalse(MetalogFeasibilityChecker.isFeasible2Term(Double.NaN))
        assertFalse(MetalogFeasibilityChecker.isFeasible2Term(Double.POSITIVE_INFINITY))
    }

    @Test
    fun twoTermRoutesThroughTheExactCondition() {
        assertTrue(checker.isFeasible(doubleArrayOf(5.0, 2.0)))
        assertFalse(checker.isFeasible(doubleArrayOf(5.0, -2.0)))
    }

    @Test
    fun theExactTwoTermConditionAgreesWithTheDenseScan() {
        for (a2 in doubleArrayOf(-5.0, -1.0, -0.001, 0.001, 1.0, 5.0)) {
            val a = doubleArrayOf(3.0, a2)
            assertEquals(
                feasibleByDenseScan(a),
                MetalogFeasibilityChecker.isFeasible2Term(a2),
                "disagreement at a2 = $a2",
            )
        }
    }

    // -------- exact three-term condition, Keelin Proposition 2 --------

    @Test
    fun threeTermRatioLimitBracketsKeelinsValue() {
        // Feasible strictly below the limit, infeasible at or above it.
        assertTrue(MetalogFeasibilityChecker.isFeasible3Term(1.0, 1.60))
        assertTrue(MetalogFeasibilityChecker.isFeasible3Term(1.0, 1.667))
        assertFalse(MetalogFeasibilityChecker.isFeasible3Term(1.0, 1.6672))
        assertFalse(MetalogFeasibilityChecker.isFeasible3Term(1.0, 1.70))
        // Symmetric in the sign of the third coefficient.
        assertTrue(MetalogFeasibilityChecker.isFeasible3Term(1.0, -1.667))
        assertFalse(MetalogFeasibilityChecker.isFeasible3Term(1.0, -1.6672))
    }

    @Test
    fun threeTermRequiresAPositiveScale() {
        assertFalse(MetalogFeasibilityChecker.isFeasible3Term(0.0, 0.0))
        assertFalse(MetalogFeasibilityChecker.isFeasible3Term(-1.0, 0.0))
    }

    @Test
    fun threeTermIsScaleInvariant() {
        // Feasibility depends only on the ratio, so scaling both coefficients changes nothing.
        for (scale in doubleArrayOf(0.01, 1.0, 100.0)) {
            assertTrue(MetalogFeasibilityChecker.isFeasible3Term(scale, scale * 1.5))
            assertFalse(MetalogFeasibilityChecker.isFeasible3Term(scale, scale * 1.8))
        }
    }

    @Test
    fun theExactThreeTermConditionAgreesWithTheGridScan() {
        // Sweep the ratio across the limit and require the grid to reach the same verdict,
        // excluding a narrow band around the limit where either may round differently.
        var ratio = 0.0
        while (ratio < 3.0) {
            if (abs(ratio - MetalogFeasibilityChecker.THREE_TERM_RATIO_LIMIT) > 1.0e-3) {
                val a = doubleArrayOf(0.0, 1.0, ratio)
                val exact = MetalogFeasibilityChecker.isFeasible3Term(1.0, ratio)
                assertEquals(exact, checker.check(a).feasible, "disagreement at ratio = $ratio")
            }
            ratio += 0.01
        }
    }

    @Test
    fun theExactThreeTermConditionAgreesWithTheDenseScan() {
        for (ratio in doubleArrayOf(0.0, 0.5, 1.0, 1.5, 1.66, 1.68, 2.0, 3.0)) {
            val a = doubleArrayOf(0.0, 1.0, ratio)
            assertEquals(
                feasibleByDenseScan(a),
                MetalogFeasibilityChecker.isFeasible3Term(1.0, ratio),
                "disagreement at ratio = $ratio",
            )
        }
    }

    // -------- grid scan for four or more terms --------

    @Test
    fun theGridResolvesTheInteriorAndBothTails() {
        val grid = checker.grid()
        assertTrue(grid.size > 1000, "grid had only ${grid.size} points")
        assertTrue(grid.first() <= 1.0e-9, "the smallest grid point was ${grid.first()}")
        assertTrue(grid.last() >= 1.0 - 1.0e-9, "the largest grid point was ${grid.last()}")
        assertTrue(grid.all { (it > 0.0) && (it < 1.0) }, "a grid point left the open unit interval")
        for (i in 0 until grid.size - 1) {
            assertTrue(grid[i] < grid[i + 1], "the grid was not strictly increasing at index $i")
        }
    }

    @Test
    fun theGridNeverAcceptsWhatTheDenseScanRejects() {
        // False acceptance is the dangerous error: it would let an invalid quantile function
        // reach a distribution or a random variable.
        val rng = Random(4242)
        var checked = 0
        var infeasible = 0
        for (numTerms in 4..6) {
            repeat(150) {
                val a = DoubleArray(numTerms) { rng.nextDouble(-1.0, 1.0) }
                a[1] = abs(a[1]) + 0.05
                val dense = feasibleByDenseScan(a)
                val grid = checker.isFeasible(a)
                if (!dense) {
                    infeasible++
                    assertFalse(
                        grid,
                        "the grid accepted coefficients the dense scan rejected: ${a.joinToString()}",
                    )
                }
                checked++
            }
        }
        // Confirm the sample actually exercised the infeasible branch rather than passing
        // trivially because everything happened to be feasible.
        assertTrue(infeasible > checked / 10, "only $infeasible of $checked vectors were infeasible")
    }

    @Test
    fun knownInfeasibleFitIsRejected() {
        // Least squares fit of x = (1, 2, 100) at y = (0.1, 0.5, 0.9); the quantile function
        // that results is not monotone. This vector drives the linear program fallback later.
        val a = doubleArrayOf(2.0, 22.52842085901421, 55.18325311425224)
        assertFalse(checker.isFeasible(a))
        assertFalse(MetalogFeasibilityChecker.isFeasible3Term(a[1], a[2]))
    }

    @Test
    fun aLogisticIsFeasibleAndAUniformIsFeasible() {
        // Two terms with a positive scale is a logistic.
        assertTrue(checker.isFeasible(doubleArrayOf(0.0, 1.0)))
        // Four terms with only the first and fourth nonzero is a uniform.
        assertTrue(checker.isFeasible(doubleArrayOf(10.0, 0.0, 0.0, 4.0)))
        // Reversing the sign of that fourth coefficient reverses the ordering.
        assertFalse(checker.isFeasible(doubleArrayOf(10.0, 0.0, 0.0, -4.0)))
    }

    // -------- reported diagnostics --------

    @Test
    fun theResultReportsWhereTheDerivativeWasSmallest() {
        val a = doubleArrayOf(0.0, 1.0, 1.5)
        val result = checker.check(a)
        assertTrue(result.feasible)
        assertTrue(result.minimumDerivative > 0.0)
        assertTrue(
            (result.worstProbability > 0.0) && (result.worstProbability < 1.0),
            "the worst probability ${result.worstProbability} left the open unit interval",
        )
        // The reported minimum must be attained at the reported probability.
        assertNearly(
            MetalogFunctions.quantileDerivative(a, result.worstProbability),
            result.minimumDerivative,
        )
    }

    @Test
    fun anInfeasibleResultReportsANonPositiveMinimum() {
        val result = checker.check(doubleArrayOf(0.0, 1.0, 3.0))
        assertFalse(result.feasible)
        assertTrue(result.minimumDerivative <= 0.0, "minimum was ${result.minimumDerivative}")
    }

    @Test
    fun nonFiniteCoefficientsAreRejectedRatherThanThrowing() {
        assertFalse(checker.isFeasible(doubleArrayOf(0.0, Double.NaN)))
        assertFalse(checker.isFeasible(doubleArrayOf(0.0, Double.POSITIVE_INFINITY)))
        assertFalse(checker.isFeasible(doubleArrayOf(0.0, 1.0, 0.0, Double.NaN)))
        val result = checker.check(doubleArrayOf(0.0, 1.0, 0.0, Double.NaN))
        assertFalse(result.feasible)
    }

    // -------- configuration and validation --------

    @Test
    fun aCoarserGridStillProducesAConsistentResult() {
        val coarse = MetalogFeasibilityChecker(uniformStep = 0.01, tailDecades = 4)
        assertTrue(coarse.gridSize < checker.gridSize)
        assertTrue(coarse.isFeasible(doubleArrayOf(0.0, 1.0, 0.0, 0.5)))
        assertFalse(coarse.isFeasible(doubleArrayOf(0.0, 1.0, 3.0)))
    }

    @Test
    fun constructorArgumentsAreValidated() {
        assertFailsWith<IllegalArgumentException>("non-positive step") {
            MetalogFeasibilityChecker(uniformStep = 0.0)
        }
        assertFailsWith<IllegalArgumentException>("step of one half or more") {
            MetalogFeasibilityChecker(uniformStep = 0.5)
        }
        assertFailsWith<IllegalArgumentException>("no tail decades") {
            MetalogFeasibilityChecker(tailDecades = 0)
        }
    }

    @Test
    fun fewerThanTwoCoefficientsIsRejected() {
        assertFailsWith<IllegalArgumentException> { checker.isFeasible(doubleArrayOf(1.0)) }
        assertFailsWith<IllegalArgumentException> { checker.check(doubleArrayOf(1.0)) }
    }

    @Test
    fun theSharedDefaultCheckerIsUsable() {
        assertTrue(MetalogFeasibilityChecker.defaultChecker.isFeasible(doubleArrayOf(0.0, 1.0)))
        assertEquals(
            MetalogFeasibilityChecker.DEFAULT_UNIFORM_STEP,
            MetalogFeasibilityChecker.defaultChecker.uniformStep,
        )
    }
}
