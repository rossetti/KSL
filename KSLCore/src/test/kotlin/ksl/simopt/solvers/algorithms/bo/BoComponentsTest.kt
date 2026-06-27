package ksl.simopt.solvers.algorithms.bo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for the Bayesian optimization components: the RBF kernel, the Gaussian-process
 *  surrogate (interpolation, predictive variance, marginal likelihood), and the acquisition
 *  functions. These pin the numerical behavior that the solver relies on.
 */
class BoComponentsTest {

    // ---- Kernel ----

    @Test
    fun rbfKernelIsMaximalAndSymmetricAndDecaysWithDistance() {
        val kernel = RBFKernel(signalVariance = 2.0, lengthScales = doubleArrayOf(1.0, 1.0))
        val a = doubleArrayOf(0.0, 0.0)
        val b = doubleArrayOf(1.0, 0.0)
        val c = doubleArrayOf(3.0, 0.0)
        assertEquals(2.0, kernel.cov(a, a), 1e-12, "cov(x,x) equals the signal variance")
        assertEquals(kernel.cov(a, b), kernel.cov(b, a), 1e-12, "the kernel must be symmetric")
        assertTrue(kernel.cov(a, b) > kernel.cov(a, c), "covariance must decay with distance")
        assertTrue(kernel.cov(a, c) > 0.0, "the RBF kernel is strictly positive")
    }

    // ---- Gaussian process ----

    @Test
    fun gpInterpolatesNoiseFreeTrainingPoints() {
        val pd = BoTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 5.0)
        val gp = GaussianProcessModel(pd, kernel = RBFKernel(signalVariance = 4.0, lengthScales = doubleArrayOf(1.0)))
        val points = listOf(doubleArrayOf(0.0), doubleArrayOf(1.0), doubleArrayOf(2.0), doubleArrayOf(3.0), doubleArrayOf(4.0))
        val means = DoubleArray(points.size) { (points[it][0] - 2.0) * (points[it][0] - 2.0) } // (x-2)^2
        val noiseVars = DoubleArray(points.size) { 0.0 }
        gp.fit(points, means, noiseVars)
        for (i in points.indices) {
            val pred = gp.predict(points[i])
            assertEquals(means[i], pred.mean, 1e-3, "the GP should interpolate noise-free training means")
            assertTrue(pred.variance < 1e-2, "predictive variance at a training point should be near zero (was ${pred.variance})")
        }
    }

    @Test
    fun gpPredictiveVarianceGrowsAwayFromData() {
        val pd = BoTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 10.0)
        val gp = GaussianProcessModel(pd, kernel = RBFKernel(signalVariance = 1.0, lengthScales = doubleArrayOf(1.0)))
        val points = listOf(doubleArrayOf(2.0), doubleArrayOf(3.0))
        gp.fit(points, doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0))
        val nearVar = gp.predict(doubleArrayOf(2.5)).variance
        val farVar = gp.predict(doubleArrayOf(9.0)).variance
        assertTrue(farVar > nearVar, "predictive variance should be larger far from the data ($farVar vs $nearVar)")
    }

    @Test
    fun gpReproducesAKnownQuadratic() {
        val pd = BoTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 5.0)
        val gp = GaussianProcessModel(pd, kernel = RBFKernel(signalVariance = 4.0, lengthScales = doubleArrayOf(1.0)))
        val points = (0..5).map { doubleArrayOf(it.toDouble()) }
        val means = DoubleArray(points.size) { (points[it][0] - 2.0) * (points[it][0] - 2.0) }
        gp.fit(points, means, DoubleArray(points.size) { 0.0 })
        val pred = gp.predict(doubleArrayOf(2.5))
        assertEquals(0.25, pred.mean, 0.5, "the GP should approximate (2.5-2)^2 = 0.25 between samples")
    }

    @Test
    fun gpLogMarginalLikelihoodIsFinite() {
        val pd = BoTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 5.0)
        val gp = GaussianProcessModel(pd, kernel = RBFKernel(signalVariance = 1.0, lengthScales = doubleArrayOf(1.5)))
        val points = (0..4).map { doubleArrayOf(it.toDouble()) }
        val means = DoubleArray(points.size) { points[it][0] }
        gp.fit(points, means, DoubleArray(points.size) { 0.1 })
        assertTrue(gp.logMarginalLikelihood().isFinite(), "the log marginal likelihood must be finite")
    }

    // ---- Acquisition functions ----

    private val pd = BoTestSupport.boxProblem(dim = 2)
    private fun solver() = BoTestSupport.makeSolver(pd, BoTestSupport.sphere(doubleArrayOf(0.0, 0.0)), streamNum = 1)

    @Test
    fun expectedImprovementWithZeroVarianceIsTheRawImprovement() {
        val ei = ExpectedImprovement(xi = 0.0)
        val s = solver()
        val improving = ei.value(SurrogateModelIfc.Prediction(mean = 2.0, variance = 0.0), incumbent = 5.0, bo = s)
        val notImproving = ei.value(SurrogateModelIfc.Prediction(mean = 7.0, variance = 0.0), incumbent = 5.0, bo = s)
        assertEquals(3.0, improving, 1e-12, "with no variance EI equals max(incumbent - mean, 0)")
        assertEquals(0.0, notImproving, 1e-12, "no improvement yields zero EI")
    }

    @Test
    fun expectedImprovementIncreasesWithUncertainty() {
        val ei = ExpectedImprovement(xi = 0.0)
        val s = solver()
        // mean == incumbent, so improvement is driven entirely by uncertainty
        val low = ei.value(SurrogateModelIfc.Prediction(mean = 5.0, variance = 0.25), incumbent = 5.0, bo = s)
        val high = ei.value(SurrogateModelIfc.Prediction(mean = 5.0, variance = 4.0), incumbent = 5.0, bo = s)
        assertTrue(high > low, "EI should increase with predictive uncertainty ($high vs $low)")
    }

    @Test
    fun lowerConfidenceBoundUsesTheClosedForm() {
        val lcb = LowerConfidenceBound(beta = 2.0)
        val s = solver()
        val value = lcb.value(SurrogateModelIfc.Prediction(mean = 3.0, variance = 4.0), incumbent = 0.0, bo = s)
        // beta*sd - mean = 2*2 - 3 = 1
        assertEquals(1.0, value, 1e-12)
    }

    @Test
    fun probabilityOfImprovementWithZeroVarianceIsZeroOrOne() {
        val pi = ProbabilityOfImprovement(xi = 0.0)
        val s = solver()
        assertEquals(1.0, pi.value(SurrogateModelIfc.Prediction(2.0, 0.0), 5.0, s), 1e-12)
        assertEquals(0.0, pi.value(SurrogateModelIfc.Prediction(7.0, 0.0), 5.0, s), 1e-12)
    }
}
