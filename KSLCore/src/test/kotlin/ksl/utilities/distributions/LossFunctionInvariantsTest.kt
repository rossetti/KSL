package ksl.utilities.distributions

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Invariants that hold for **every** implementor of [LossFunctionDistributionIfc], checked against
 * brute-force summation (discrete) and Simpson quadrature (continuous) rather than against restated
 * closed forms.
 *
 * Why a shared suite rather than per-distribution tests. The loss functions are the entry point for
 * `ksl.utilities.misc.RQInventoryModel`, whose fill rate, stockout probability, expected backorders
 * and expected on-hand inventory are all computed from them — so an error here becomes wrong
 * inventory performance measures with nothing raised. The suite that existed covered `Poisson` and
 * `Binomial`, which is exactly why those two are correct and two others are not.
 *
 * **How to trust this suite.** Seven implementations were independently verified correct against
 * quadrature and against published closed forms: `Poisson`, `Geometric`, `NegativeBinomial`,
 * `Binomial`, `Normal`, `Gamma` and `Lognormal`. They are included here deliberately. If the
 * reference implementations below were wrong, those seven would fail too — so a run in which only
 * the suspected distributions fail is evidence about the library, and a run in which a verified one
 * fails is evidence about this file.
 */
class LossFunctionInvariantsTest {

    /**
     * One distribution under test.
     *
     * @param label what appears in the test name
     * @param distribution the instance
     * @param isNonNegative whether the support excludes negative values — `G1(0) = E[X]` holds only
     * then, since `G1(0)` is `E[X⁺]`
     * @param isDiscrete whether to check against summation rather than quadrature
     */
    data class Case(
        val label: String,
        val distribution: LossFunctionDistributionIfc,
        val isNonNegative: Boolean,
        val isDiscrete: Boolean
    ) {
        override fun toString(): String = label
    }

    companion object {

        /** Support points of the empirical fixture, with E[X] = 7.15. */
        private val EMPIRICAL_VALUES = doubleArrayOf(2.0, 5.0, 9.0, 14.0)
        private val EMPIRICAL_CDF = doubleArrayOf(0.20, 0.50, 0.85, 1.00)

        @JvmStatic
        fun cases(): List<Case> = listOf(
            // --- verified correct by independent checking; they validate this suite -------------
            Case("Poisson(6)", Poisson(6.0), isNonNegative = true, isDiscrete = true),
            Case("Binomial(0.3,20)", Binomial(0.3, 20), isNonNegative = true, isDiscrete = true),
            Case("NegativeBinomial(0.3,5)", NegativeBinomial(0.3, 5.0), isNonNegative = true, isDiscrete = true),
            Case("Geometric(0.25)", Geometric(0.25), isNonNegative = true, isDiscrete = true),
            Case("Normal(10,4)", Normal(10.0, 4.0), isNonNegative = false, isDiscrete = false),
            Case("Gamma(2,3)", Gamma(2.0, 3.0), isNonNegative = true, isDiscrete = false),
            Case("Lognormal(10,4)", Lognormal(10.0, 4.0), isNonNegative = true, isDiscrete = false),
            // --- under suspicion ----------------------------------------------------------------
            // Exponential's default mean is 1.0, at which a rate and a mean parameterization
            // coincide; every other mean is the interesting case.
            Case("Exponential(1.0)", Exponential(1.0), isNonNegative = true, isDiscrete = false),
            Case("Exponential(2.5)", Exponential(2.5), isNonNegative = true, isDiscrete = false),
            Case("Exponential(10.0)", Exponential(10.0), isNonNegative = true, isDiscrete = false),
            Case(
                "DEmpiricalCDF(2,5,9,14)",
                DEmpiricalCDF(EMPIRICAL_VALUES, EMPIRICAL_CDF),
                isNonNegative = true, isDiscrete = true
            )
        )

        /**
         * Arguments every case is probed at, whole numbers and fractions alike.
         *
         * 1.5, 4.5, 8.5 and 13.5 are here deliberately: they sit strictly between the empirical
         * fixture's support points and their successors, which is the only band where the second
         * order loss function's SECOND factor is negative while its first is positive. An
         * implementation clamping only the first factor is correct everywhere else and negative
         * there, so without these arguments the suite could not tell the two apart.
         *
         * The negative arguments matter for a different reason. Below zero the truncation never
         * binds for a non-negative random variable, so `G1(x) = E[X] - x` exactly; the branches that
         * used to handle this wrote `floor(abs(x)) + mean`, which is right at a negative whole
         * number and wrong between them. Probing only non-negative arguments would leave that
         * unexercised.
         */
        private val PROBE_ARGUMENTS: List<Double> =
            listOf(
                -2.5, -2.0, -1.5, -1.0, -0.5,
                0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 4.5, 5.0, 6.5, 8.0, 8.5,
                10.0, 12.0, 13.5, 15.0
            )

        /** A ceiling on the summation so a mistake cannot hang the suite; never reached in practice. */
        private const val HARD_SUM_CAP = 500_000

        /** Whole-number arguments only, where every discrete implementation claims to be exact. */
        private val WHOLE_ARGUMENTS: List<Double> =
            PROBE_ARGUMENTS.filter { it == floor(it) }
    }

    // ── Reference implementations ─────────────────────────────────────────────
    //
    // Deliberately written from the definitions and nothing else:
    //   G1(b) = E[(X-b)+]
    //   G2(b) = (1/2) E[(X-b)+ (X-b-1)+]   discrete   (the second factorial moment form)
    //   G2(b) = (1/2) E[((X-b)+)^2]        continuous

    /**
     * Sums a weighted pmf over the support, extending until the terms have been negligible for long
     * enough that the remainder cannot matter at the tolerances used.
     *
     * A fixed upper limit is not good enough, and getting that wrong is how this suite first accused
     * a correct implementation: `Geometric(0.25)` has a heavy tail, and truncating at mean + 25 sd
     * left `G2` short by 6e-9 against a library answer that was exactly right. A reference that is
     * less accurate than the tolerance it enforces reports its own error as someone else's defect.
     */
    private fun weightedSum(d: LossFunctionDistributionIfc, weight: (Int) -> Double): Double {
        val pmf = d as PMFIfc
        var total = 0.0
        var negligibleRun = 0
        var k = 0
        while (k <= HARD_SUM_CAP) {
            val w = weight(k)
            if (w > 0.0) {
                val term = w * pmf.pmf(k)
                total += term
                // The weight grows at most polynomially while the mass decays, so once the terms
                // have been vanishing for a stretch they stay that way.
                if (term <= 1.0e-18 * max(1.0, total)) negligibleRun++ else negligibleRun = 0
                if (negligibleRun >= 50) break
            }
            k++
        }
        return total
    }

    private fun bruteForceG1Discrete(d: LossFunctionDistributionIfc, b: Double): Double =
        weightedSum(d) { k -> k - b }

    private fun bruteForceG2Discrete(d: LossFunctionDistributionIfc, b: Double): Double =
        0.5 * weightedSum(d) { k ->
            val first = k - b
            val second = k - b - 1.0
            if (first > 0.0 && second > 0.0) first * second else 0.0
        }

    /** Composite Simpson over [b, upper] with an even number of panels. */
    private fun simpson(lower: Double, upper: Double, panels: Int, f: (Double) -> Double): Double {
        require(panels % 2 == 0) { "Simpson needs an even panel count" }
        if (upper <= lower) return 0.0
        val h = (upper - lower) / panels
        var sum = f(lower) + f(upper)
        for (i in 1 until panels) {
            sum += (if (i % 2 == 0) 2.0 else 4.0) * f(lower + i * h)
        }
        return sum * h / 3.0
    }

    /** Far enough into the tail that what remains cannot move the answer at the tolerances used. */
    private fun continuousUpperLimit(d: LossFunctionDistributionIfc): Double =
        (d as InverseCDFIfc).invCDF(1.0 - 1.0e-12)

    /**
     *  Where the integration starts.
     *
     *  Below the support the density is zero and contributes nothing, so starting at the support's
     *  edge rather than at [b] is not an approximation — and it avoids integrating across a
     *  discontinuity. That matters: the exponential density jumps from 0 to `1/mean` at the origin,
     *  and Simpson's rule assumes smoothness, so integrating from a negative lower limit misread
     *  `G1(-2)` as 3.0008 against an exact 3.0. Gamma and Lognormal hid the problem because their
     *  densities approach zero smoothly there.
     */
    private fun integrationLowerLimit(case: Case, b: Double): Double =
        if (case.isNonNegative) max(b, 0.0) else b

    private fun quadratureG1(case: Case, b: Double): Double {
        val pdf = case.distribution as PDFIfc
        return simpson(integrationLowerLimit(case, b), continuousUpperLimit(case.distribution), 20_000) { x ->
            (x - b) * pdf.pdf(x)
        }
    }

    private fun quadratureG2(case: Case, b: Double): Double {
        val pdf = case.distribution as PDFIfc
        return 0.5 * simpson(integrationLowerLimit(case, b), continuousUpperLimit(case.distribution), 20_000) { x ->
            (x - b) * (x - b) * pdf.pdf(x)
        }
    }

    private fun referenceG1(case: Case, b: Double): Double =
        if (case.isDiscrete) bruteForceG1Discrete(case.distribution, b) else quadratureG1(case, b)

    private fun referenceG2(case: Case, b: Double): Double =
        if (case.isDiscrete) bruteForceG2Discrete(case.distribution, b) else quadratureG2(case, b)

    /** Relative where the magnitude allows it, absolute near zero. */
    private fun closeEnough(expected: Double, actual: Double, tolerance: Double): Boolean {
        val scale = max(1.0, abs(expected))
        return abs(expected - actual) <= tolerance * scale
    }

    // ── Invariant 1 ───────────────────────────────────────────────────────────

    /**
     * `G1(0) = E[(X-0)⁺] = E[X]` whenever the support is non-negative. One line, no reference
     * implementation needed, and it pins the single most basic property a loss function has.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("Invariant 1: G1(0) equals the mean for a non-negative random variable")
    fun firstOrderLossAtZeroIsTheMean(case: Case) {
        if (!case.isNonNegative) return
        val mean = case.distribution.mean()
        val g1 = case.distribution.firstOrderLossFunction(0.0)
        assertTrue(closeEnough(mean, g1, 1.0e-8)) {
            "${case.label}: G1(0) = $g1 but E[X] = $mean"
        }
    }

    // ── Invariant 2 ───────────────────────────────────────────────────────────

    /**
     * `G2` is half the expectation of a product of two non-negative quantities, so it cannot be
     * negative at any argument. A single negative value condemns an implementation without anyone
     * having to know the right answer.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("Invariant 2: G2 is never negative")
    fun secondOrderLossIsNeverNegative(case: Case) {
        val offenders = PROBE_ARGUMENTS
            .map { it to case.distribution.secondOrderLossFunction(it) }
            .filter { (_, g2) -> g2 < -1.0e-9 }
        assertTrue(offenders.isEmpty()) {
            "${case.label}: G2 is negative at " +
                offenders.joinToString { (x, g2) -> "x=$x -> $g2" }
        }
    }

    // ── Invariant 3 ───────────────────────────────────────────────────────────

    /**
     * Agreement with the definitions, at whole-number arguments only. Every implementation claims
     * to be exact here, discrete and continuous alike, so a failure is unambiguous.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("Invariant 3: G1 and G2 agree with summation or quadrature at whole numbers")
    fun lossFunctionsAgreeWithTheDefinitionAtWholeNumbers(case: Case) {
        val tolerance = if (case.isDiscrete) 1.0e-9 else 1.0e-5
        val failures = mutableListOf<String>()
        for (b in WHOLE_ARGUMENTS) {
            val expected1 = referenceG1(case, b)
            val actual1 = case.distribution.firstOrderLossFunction(b)
            if (!closeEnough(expected1, actual1, tolerance)) {
                failures.add("G1($b): expected $expected1, got $actual1")
            }
            val expected2 = referenceG2(case, b)
            val actual2 = case.distribution.secondOrderLossFunction(b)
            if (!closeEnough(expected2, actual2, tolerance)) {
                failures.add("G2($b): expected $expected2, got $actual2")
            }
        }
        assertTrue(failures.isEmpty()) {
            "${case.label} disagrees with the definition at whole numbers:\n  " +
                failures.joinToString("\n  ")
        }
    }

    /**
     * The same agreement at fractional arguments. `G1(x) = E[(X-x)⁺]` is defined for every real `x`
     * and is continuous and decreasing in it, so a caller comparing a quantity that came from a
     * continuous model against a discrete one — pricing a normal-approximation order quantity under
     * a fitted negative binomial, say — has no reason to expect whole numbers to be required.
     *
     * Split from the whole-number check so that a distribution exact on integers and wrong between
     * them is reported as exactly that, rather than as simply broken.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("Invariant 3b: G1 and G2 agree with summation or quadrature between whole numbers")
    fun lossFunctionsAgreeWithTheDefinitionAtFractionalArguments(case: Case) {
        val fractional = PROBE_ARGUMENTS.filter { it != floor(it) }
        val tolerance = if (case.isDiscrete) 1.0e-9 else 1.0e-5
        val failures = mutableListOf<String>()
        for (b in fractional) {
            val expected1 = referenceG1(case, b)
            val actual1 = case.distribution.firstOrderLossFunction(b)
            if (!closeEnough(expected1, actual1, tolerance)) {
                failures.add("G1($b): expected $expected1, got $actual1")
            }
            val expected2 = referenceG2(case, b)
            val actual2 = case.distribution.secondOrderLossFunction(b)
            if (!closeEnough(expected2, actual2, tolerance)) {
                failures.add("G2($b): expected $expected2, got $actual2")
            }
        }
        assertTrue(failures.isEmpty()) {
            "${case.label} disagrees with the definition between whole numbers:\n  " +
                failures.joinToString("\n  ")
        }
    }
}
