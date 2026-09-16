package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.MixtureDistribution
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.fitting.estimators.ExponentialMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureAICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureICLBICCriterion
import ksl.utilities.random.rvariable.NormalRV
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FittingLayerTest {

    // ------------------------------------------------------------------- log-likelihood

    @Test
    fun `log mixture density matches the direct computation when no underflow occurs`() {
        val components = listOf(Normal(0.0, 1.0), Normal(3.0, 1.0))
        val weights = doubleArrayOf(0.4, 0.6)
        for (x in doubleArrayOf(-1.0, 0.0, 1.5, 3.0, 5.0)) {
            val direct = ln(0.4 * components[0].pdf(x) + 0.6 * components[1].pdf(x))
            val stable = MixtureLogLikelihood.logMixtureDensity(weights, components, x)
            assertEquals(direct, stable, 1e-10, "at x=$x")
        }
    }

    @Test
    fun `log sum exp survives separation that underflows the direct computation`() {
        // At x = 0 the far component's density is about exp(-5000), which underflows to zero.
        val components = listOf(Normal(0.0, 1.0), Normal(100.0, 1.0))
        val weights = doubleArrayOf(0.5, 0.5)
        val naive = 0.5 * components[0].pdf(0.0) + 0.5 * components[1].pdf(0.0)
        val stable = MixtureLogLikelihood.logMixtureDensity(weights, components, 0.0)
        assertTrue(stable.isFinite(), "the stable value must remain finite")
        assertEquals(ln(naive), stable, 1e-9, "both agree here; the point is that neither is -inf")
        // and the stable form must equal the surviving term alone to high accuracy
        assertEquals(ln(0.5) + ln(components[0].pdf(0.0)), stable, 1e-9)
    }

    @Test
    fun `zero density gives negative infinity and is counted, not floored`() {
        // an exponential has zero density below zero
        val components = listOf(Exponential(1.0), Exponential(2.0))
        val weights = doubleArrayOf(0.5, 0.5)
        val data = doubleArrayOf(-1.0, 1.0, 2.0)
        val result = MixtureLogLikelihood.evaluate(data, weights, components)
        assertEquals(Double.NEGATIVE_INFINITY, result.value)
        assertEquals(1, result.numZeroDensity)
        assertFalse(result.isUsable)
    }

    @Test
    fun `responsibilities sum to one and are undefined where the density vanishes`() {
        val components = listOf(Exponential(1.0), Exponential(3.0))
        val weights = doubleArrayOf(0.5, 0.5)
        val data = doubleArrayOf(1.0, -1.0)
        val r = MixtureLogLikelihood.responsibilities(data, weights, components)
        assertEquals(1.0, r[0].sum(), 1e-12)
        assertTrue(r[1].all { it.isNaN() }, "no responsibility is defined at a zero density")
    }

    @Test
    fun `classification entropy is zero for a perfect split and positive when components overlap`() {
        val separated = arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))
        assertEquals(0.0, MixtureLogLikelihood.classificationEntropy(separated), 1e-12)
        val overlapping = arrayOf(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.5, 0.5))
        assertTrue(MixtureLogLikelihood.classificationEntropy(overlapping) > 0.0)
    }

    // ------------------------------------------------------------------------- fitting

    @Test
    fun `constant data is rejected with a reason rather than throwing`() {
        // The normal estimator reports success on constant data with a zero variance, and the
        // distribution constructor then rejects that variance by throwing. Success is therefore
        // necessary but not sufficient, and the fitter must absorb the difference.
        val data = DoubleArray(20) { 5.0 }
        val fitter = PDFComponentFitter(setOf(NormalMLEParameterEstimator))
        val result = fitter.fitGroup(data, 0, data.size)
        assertEquals(0, result.startIndex)
        assertEquals(data.size, result.endIndex)
        assertFalse(result.hasCandidates, "a zero-variance normal is not a usable component")
        assertTrue(result.rejections.isNotEmpty(), "the reason must be reported")
        assertTrue(
            result.rejections.any { it.message.contains("could not be constructed") },
            "the estimator's own failure mode should be named: ${result.rejections}"
        )
    }

    @Test
    fun `the certificate prevents the constant-group case upstream`() {
        // The distinct-value requirement makes a constant group structurally impossible, so the
        // fitter's guard above is a second line of defence rather than the only one.
        val data = DoubleArray(20) { 5.0 }
        val certificate = AdmissibilityCertificate(data, 1, 2)
        assertEquals(0, certificate.maximumFeasibleGroups)
        assertFalse(certificate.isFeasible(1))
        assertFalse(certificate.isValidGroup(0, data.size))
    }

    @Test
    fun `an exponential estimator rejects data with negative values`() {
        val data = doubleArrayOf(-3.0, -2.0, -1.0, 1.0, 2.0, 3.0)
        val fitter = PDFComponentFitter(setOf(ExponentialMLEParameterEstimator))
        val result = fitter.fitGroup(data, 0, data.size)
        assertFalse(result.hasCandidates, "an exponential cannot be fitted to negative data")
        assertTrue(result.rejections.isNotEmpty(), "the reason must be reported")
    }

    @Test
    fun `the cache reproduces uncached results exactly and reports its hit rate`() {
        val rv = NormalRV(10.0, 2.0, streamNum = 1)
        val data = rv.sample(200).sortedArray()
        val plain = PDFComponentFitter(setOf(NormalMLEParameterEstimator))
        val cached = ComponentFitCache(PDFComponentFitter(setOf(NormalMLEParameterEstimator)))

        val direct = plain.fitGroup(data, 0, 100)
        val first = cached.fitGroup(data, 0, 100)
        val second = cached.fitGroup(data, 0, 100)

        assertEquals(direct.candidates.size, first.candidates.size)
        assertEquals(first.candidates.size, second.candidates.size)
        if (direct.hasCandidates) {
            assertEquals(direct.candidates[0].name, first.candidates[0].name)
            assertEquals(first.candidates[0].name, second.candidates[0].name)
        }
        assertEquals(1, cached.misses)
        assertEquals(1, cached.hits)
        assertEquals(0.5, cached.hitRate, 1e-12)
    }

    @Test
    fun `the cache key matches the partition range key`() {
        val p = DataPartition(20, intArrayOf(7, 13))
        for (g in 0 until p.numGroups) {
            assertEquals(
                p.rangeKey(g),
                ComponentFitCache.keyFor(p.startIndex(g), p.endIndex(g)),
                "group $g"
            )
        }
    }

    // ---------------------------------------------------------------------- candidates

    @Test
    fun `free parameter count charges one fewer weight than components`() {
        val rv = NormalRV(0.0, 1.0, streamNum = 2)
        val data = rv.sample(60).sortedArray()
        val fitter = PDFComponentFitter(setOf(NormalMLEParameterEstimator))
        val partition = DataPartition(data.size, intArrayOf(30))
        val fits = fitter.fitAll(data, partition)
        val candidate = MixtureCandidate.allCombinations(partition, fits).first()
        // two normals: 2 parameters each, plus one free weight
        assertEquals(2, candidate.numComponents)
        assertEquals(5, candidate.numFreeParameters)
        // the assembled distribution reports one parameter per weight, hence one more
        assertEquals(6, candidate.toMixtureDistribution().parameters().size)
    }

    @Test
    fun `weights follow the group proportions and the mixture cdf ends at one`() {
        val rv = NormalRV(0.0, 1.0, streamNum = 3)
        val data = rv.sample(100).sortedArray()
        val fitter = PDFComponentFitter(setOf(NormalMLEParameterEstimator))
        val partition = DataPartition(data.size, intArrayOf(40))
        val fits = fitter.fitAll(data, partition)
        val candidate = MixtureCandidate.allCombinations(partition, fits).first()
        assertEquals(0.40, candidate.weights[0], 1e-12)
        assertEquals(0.60, candidate.weights[1], 1e-12)
        val md = candidate.toMixtureDistribution()
        // two components, so the assembled object really is a mixture and carries a mixing CDF
        assertTrue(md is MixtureDistribution, "two components must assemble to a mixture")
        assertEquals(1.0, (md as MixtureDistribution).mixingCDF.last(), 1e-12)
    }

    @Test
    fun `a single component is returned as itself rather than as a mixture of one`() {
        // The library's mixture distribution requires two or more components, so a one-component
        // candidate cannot be wrapped. It is how a single distribution is expressed here, and
        // comparing a mixture against one is the first thing a user does, so this path has to
        // work rather than throw.
        val rv = NormalRV(0.0, 1.0, streamNum = 5)
        val data = rv.sample(60).sortedArray()
        val fitter = PDFComponentFitter(setOf(NormalMLEParameterEstimator))
        val partition = DataPartition(data.size, intArrayOf())
        val fits = fitter.fitAll(data, partition)
        val candidate = MixtureCandidate.allCombinations(partition, fits).first()
        assertEquals(1, candidate.numComponents)
        assertEquals(1.0, candidate.weights[0], 1e-12)

        val single = candidate.toMixtureDistribution()
        assertFalse(single is MixtureDistribution, "one component is not a mixture")
        // and it is usable: the diagnostics and the criteria all take a continuous distribution
        assertEquals(candidate.distributions.first().mean(), single.mean(), 1e-12)
        assertTrue(single.pdf(0.0) > 0.0)
    }

    @Test
    fun `no combinations are produced when a group has no candidates`() {
        val data = doubleArrayOf(-3.0, -2.0, -1.0, 1.0, 2.0, 3.0)
        val fitter = PDFComponentFitter(setOf(ExponentialMLEParameterEstimator))
        val partition = DataPartition(6, intArrayOf(3))
        val fits = fitter.fitAll(data, partition)
        assertEquals(0, MixtureCandidate.allCombinations(partition, fits).count())
    }

    // ------------------------------------------------------------------------ criteria

    @Test
    fun `a criterion bounds and flags a candidate that cannot cover the data`() {
        // exponentials cannot cover negative observations, so the likelihood is not finite
        val data = doubleArrayOf(-2.0, -1.0, 1.0, 2.0, 3.0, 4.0)
        val partition = DataPartition(6, intArrayOf(3))
        val candidate = MixtureCandidate(
            partition,
            listOf(
                ComponentCandidateFixture.of(Exponential(1.0), 1),
                ComponentCandidateFixture.of(Exponential(2.0), 1)
            )
        )
        val value = MixtureBICCriterion().evaluate(candidate, data)
        assertFalse(value.isComparable, "a non-finite likelihood must not be ranked as if valid")
        assertEquals(2, value.numZeroDensity)
        assertNotNull(value.message)
        assertTrue(value.value.isFinite(), "the bound must be a finite number, not an infinity")
    }

    @Test
    fun `BIC matches the definition for a finite likelihood`() {
        val rv = NormalRV(0.0, 1.0, streamNum = 4)
        val data = rv.sample(100).sortedArray()
        val partition = DataPartition(data.size, intArrayOf(50))
        val candidate = MixtureCandidate(
            partition,
            listOf(
                ComponentCandidateFixture.of(Normal(-0.8, 0.4), 2),
                ComponentCandidateFixture.of(Normal(0.8, 0.4), 2)
            )
        )
        val ll = candidate.logLikelihood(data)
        assertTrue(ll.isUsable)
        val expected = candidate.numFreeParameters * ln(data.size.toDouble()) - 2.0 * ll.value
        val actual = MixtureBICCriterion().evaluate(candidate, data)
        assertTrue(actual.isComparable)
        assertEquals(expected, actual.value, 1e-8)
    }

    @Test
    fun `ICL-BIC exceeds BIC by twice the classification entropy`() {
        val rv = NormalRV(0.0, 1.0, streamNum = 5)
        val data = rv.sample(100).sortedArray()
        val partition = DataPartition(data.size, intArrayOf(50))
        val candidate = MixtureCandidate(
            partition,
            listOf(
                ComponentCandidateFixture.of(Normal(-0.5, 1.0), 2),
                ComponentCandidateFixture.of(Normal(0.5, 1.0), 2)
            )
        )
        val bic = MixtureBICCriterion().evaluate(candidate, data).value
        val icl = MixtureICLBICCriterion().evaluate(candidate, data).value
        val entropy = MixtureLogLikelihood.classificationEntropy(
            MixtureLogLikelihood.responsibilities(data, candidate.weights, candidate.distributions)
        )
        assertEquals(bic + 2.0 * entropy, icl, 1e-8)
        assertTrue(icl > bic, "overlapping components must be penalized")
    }

    @Test
    fun `criteria declare their direction and AIC penalizes less than BIC for large samples`() {
        assertTrue(MixtureBICCriterion().smallerIsBetter)
        assertTrue(MixtureAICCriterion().smallerIsBetter)
        assertEquals("BIC", MixtureBICCriterion().name)
        // for n > e^2 the BIC penalty per parameter exceeds the AIC penalty
        val rv = NormalRV(0.0, 1.0, streamNum = 6)
        val data = rv.sample(500).sortedArray()
        val partition = DataPartition(data.size, intArrayOf(250))
        val candidate = MixtureCandidate(
            partition,
            listOf(
                ComponentCandidateFixture.of(Normal(-0.5, 1.0), 2),
                ComponentCandidateFixture.of(Normal(0.5, 1.0), 2)
            )
        )
        val bic = MixtureBICCriterion().evaluate(candidate, data).value
        val aic = MixtureAICCriterion().evaluate(candidate, data).value
        assertTrue(bic > aic, "BIC should penalize more heavily at n=500")
    }

    @Test
    fun `metrics carry the correct direction for use in a decision analysis`() {
        val bicMetric = MixtureBICCriterion().metric()
        assertEquals(ksl.utilities.moda.MetricIfc.Direction.SmallerIsBetter, bicMetric.direction)
        val heldOut = ksl.utilities.distributions.fitting.mixture.scoring
            .HeldOutLogLikelihoodCriterion(doubleArrayOf(1.0, 2.0, 3.0))
        assertFalse(heldOut.smallerIsBetter)
        assertEquals(
            ksl.utilities.moda.MetricIfc.Direction.BiggerIsBetter,
            heldOut.metric().direction
        )
    }

    // -------------------------------------------------------------------- end to end T0

    @Test
    fun `T0 recovers two well separated normals and selects k equal to two`() {
        val left = NormalRV(0.0, 1.0, streamNum = 11).sample(1000)
        val right = NormalRV(10.0, 1.0, streamNum = 12).sample(1000)
        val data = (left + right)
        val modeler = MixtureModeler(
            data,
            fitter = PDFComponentFitter(setOf(NormalMLEParameterEstimator)),
            criterion = MixtureBICCriterion()
        )
        val results = modeler.fit(numComponentsRange = 1..4)
        val best = results.best
        assertNotNull(best, "a candidate must be produced")
        assertEquals(2, best.candidate.numComponents, "BIC should select two components")

        val weights = best.candidate.weights.sortedArray()
        assertTrue(abs(weights[0] - 0.5) < 0.02, "weights were ${weights.toList()}")
        assertTrue(abs(weights[1] - 0.5) < 0.02, "weights were ${weights.toList()}")

        val means = best.candidate.distributions.map { it.mean() }.sorted()
        assertTrue(abs(means[0] - 0.0) < 0.3, "means were $means")
        assertTrue(abs(means[1] - 10.0) < 0.3, "means were $means")
    }

    @Test
    fun `infeasible group counts are reported rather than attempted`() {
        val data = DoubleArray(30) { if (it < 15) 1.0 else 2.0 }
        val modeler = MixtureModeler(
            data,
            fitter = PDFComponentFitter(setOf(NormalMLEParameterEstimator)),
            minimumGroupSize = 5,
            minimumDistinctValues = 2
        )
        val results = modeler.fit(numComponentsRange = 1..4)
        // two distinct values cannot give two groups each holding two distinct values
        assertTrue(results.rejectedGroupCounts.containsKey(2))
        assertNotNull(results.rejectedGroupCounts[2])
        assertEquals(1, results.certificate.maximumFeasibleGroups)
    }
}

/**
 *  Builds a component candidate directly from a distribution, for tests that need a controlled
 *  mixture rather than a fitted one.
 */
private object ComponentCandidateFixture {
    fun of(
        distribution: ksl.utilities.distributions.ContinuousDistributionIfc,
        numParameters: Int
    ): ComponentCandidate {
        val data = doubleArrayOf(0.0, 1.0)
        val result = ksl.utilities.distributions.fitting.EstimationResult(
            originalData = data,
            statistics = ksl.utilities.statistic.Statistic(data),
            parameters = null,
            message = "fixture",
            success = true,
            estimator = ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
        )
        return ComponentCandidate(
            distribution, result,
            ksl.utilities.random.rvariable.RVType.Normal, numParameters, distribution.toString()
        )
    }
}
