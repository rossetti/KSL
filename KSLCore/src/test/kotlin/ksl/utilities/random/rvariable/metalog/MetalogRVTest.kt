package ksl.utilities.random.rvariable.metalog

import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.metalog.Metalog2P
import ksl.utilities.distributions.metalog.Metalog3P
import ksl.utilities.distributions.metalog.Metalog4P
import ksl.utilities.distributions.metalog.Metalog5P
import ksl.utilities.distributions.metalog.Metalog6P
import ksl.utilities.distributions.metalog.MetalogBoundedness
import ksl.utilities.distributions.metalog.MetalogDistribution
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.ParameterizedRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.CreateDistributionIfc
import ksl.utilities.random.rvariable.parameters.Metalog2PRVParameters
import ksl.utilities.random.rvariable.parameters.Metalog3PRVParameters
import ksl.utilities.random.rvariable.parameters.Metalog4PRVParameters
import ksl.utilities.random.rvariable.parameters.Metalog5PRVParameters
import ksl.utilities.random.rvariable.parameters.Metalog6PRVParameters
import ksl.utilities.random.rvariable.parameters.RVParameters
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetalogRVTest {

    private fun assertNearly(
        expected: Double,
        actual: Double,
        relTol: Double = 1e-10,
        absTol: Double = 1e-10,
        message: String = "",
    ) {
        val diff = abs(expected - actual)
        val threshold = max(absTol, relTol * max(1.0, abs(expected)))
        assertTrue(
            diff <= threshold,
            "$message Expected $expected, actual $actual, diff $diff, threshold $threshold",
        )
    }

    private fun allArities(streamNum: Int = 1): List<ParameterizedRV> = listOf(
        Metalog2PRV(1.0, 2.0, streamNum = streamNum),
        Metalog3PRV(1.0, 2.0, 0.3, streamNum = streamNum),
        Metalog4PRV(1.0, 2.0, 0.3, 0.5, streamNum = streamNum),
        Metalog5PRV(1.0, 2.0, 0.3, 0.5, 0.1, streamNum = streamNum),
        Metalog6PRV(1.0, 2.0, 0.3, 0.5, 0.1, 0.05, streamNum = streamNum),
    )

    private fun allParameterTypes(): List<RVParameters> = listOf(
        Metalog2PRVParameters(),
        Metalog3PRVParameters(),
        Metalog4PRVParameters(),
        Metalog5PRVParameters(),
        Metalog6PRVParameters(),
    )

    // -------- stream behavior --------

    @Test
    fun theSameStreamNumberReproducesTheSameSequenceAcrossProviders() {
        // A stream number names a stream that is shared within one provider, so two random
        // variables built on the same provider and number draw from the same stream and interleave
        // rather than repeat. Reproducibility is across providers, which is what a rerun of a model
        // amounts to.
        val first = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 7, streamProvider = RNStreamProvider())
        val second = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 7, streamProvider = RNStreamProvider())
        repeat(100) { assertNearly(first.value, second.value) }
    }

    @Test
    fun twoVariablesSharingAStreamNumberShareTheStream() {
        // Documents the consequence of the above, since it is a common source of surprise: within
        // one provider the two draw alternately from one stream, so their values differ.
        val provider = RNStreamProvider()
        val first = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 7, streamProvider = provider)
        val second = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 7, streamProvider = provider)
        var differences = 0
        repeat(50) { if (abs(first.value - second.value) > 1e-12) differences++ }
        assertTrue(differences > 40, "only $differences of 50 values differed")
    }

    @Test
    fun differentStreamNumbersProduceDifferentSequences() {
        val first = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 11)
        val second = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 12)
        var differences = 0
        repeat(50) { if (abs(first.value - second.value) > 1e-12) differences++ }
        assertTrue(differences > 40, "only $differences of 50 values differed")
    }

    @Test
    fun resettingTheStreamRepeatsTheSequence() {
        // On its own provider deliberately. This test compares a sequence against itself after a
        // reset, so it is sensitive to where the stream started. Drawn from the shared default
        // provider it would depend on whether any earlier test in the same JVM had advanced that
        // stream, which several do indirectly through bootstrapping inside the fitting code.
        val rv = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 13, streamProvider = RNStreamProvider())
        val first = DoubleArray(30) { rv.value }
        rv.resetStartStream()
        val second = DoubleArray(30) { rv.value }
        assertTrue(first.contentEquals(second), "the sequence did not repeat after a reset")
    }

    @Test
    fun antitheticSamplingMirrorsTheStream() {
        // Its own provider, for the same reason as the reset test above.
        val rv = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 17, streamProvider = RNStreamProvider())
        val direct = DoubleArray(25) { rv.value }
        rv.resetStartStream()
        rv.antithetic = true
        val mirrored = DoubleArray(25) { rv.value }
        // Antithetic variates use the complement of each uniform, so a monotone quantile function
        // maps them to the value at the complementary probability.
        val distribution = Metalog3P(1.0, 2.0, 0.3)
        for (i in direct.indices) {
            val p = distribution.cdf(direct[i])
            assertNearly(
                distribution.invCDF(1.0 - p), mirrored[i],
                relTol = 1e-6, absTol = 1e-6, message = "at draw $i:",
            )
        }
    }

    @Test
    fun anInstanceOnANewStreamKeepsTheParameters() {
        val original = Metalog4PRV(1.0, 2.0, 0.3, 0.5, lowerBound = 0.0, streamNum = 3)
        val copy = original.instance(9) as Metalog4PRV
        assertNearly(original.a1, copy.a1)
        assertNearly(original.a4, copy.a4)
        assertNearly(original.lowerBound, copy.lowerBound)
        assertEquals(original.boundedness, copy.boundedness)
    }

    // -------- agreement with the distribution --------

    @Test
    fun theEmpiricalDistributionMatchesTheFittedOne() {
        val distribution = Metalog3P(10.0, 2.0, 0.5)
        val rv = Metalog3PRV(10.0, 2.0, 0.5, streamNum = 21)
        val n = 40_000
        val sample = DoubleArray(n) { rv.value }
        sample.sort()
        var maximumGap = 0.0
        for (i in 0 until n step 40) {
            val empirical = (i + 0.5) / n
            maximumGap = max(maximumGap, abs(distribution.cdf(sample[i]) - empirical))
        }
        // A Kolmogorov-Smirnov style bound at this sample size.
        assertTrue(maximumGap < 0.02, "the largest gap was $maximumGap")
    }

    @Test
    fun eachBoundednessVariantGeneratesInsideItsSupport() {
        val cases = listOf(
            Triple(Metalog3PRV(0.0, 0.5, 0.1, streamNum = 31), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
            Triple(Metalog3PRV(0.0, 0.5, 0.1, lowerBound = 4.0, streamNum = 32), 4.0, Double.POSITIVE_INFINITY),
            Triple(Metalog3PRV(0.0, 0.5, 0.1, upperBound = 9.0, streamNum = 33), Double.NEGATIVE_INFINITY, 9.0),
            Triple(
                Metalog3PRV(0.0, 0.5, 0.1, lowerBound = 2.0, upperBound = 30.0, streamNum = 34),
                2.0, 30.0
            ),
        )
        for ((rv, lower, upper) in cases) {
            repeat(3_000) {
                val x = rv.value
                assertTrue(x > lower, "${rv.boundedness}: the variate $x was not above $lower")
                assertTrue(x < upper, "${rv.boundedness}: the variate $x was not below $upper")
                assertTrue(x.isFinite(), "${rv.boundedness}: the variate was not finite")
            }
        }
    }

    @Test
    fun theDistributionHandsBackTheMatchingRandomVariableType() {
        // Reached through the interface deliberately. Each concrete distribution narrows the
        // return type of randomVariable to its own random variable class, so calling it on the
        // concrete type makes these checks true at compile time and they would prove nothing.
        // A caller holding the interface is the case that matters, and there the type is only
        // known at run time.
        val cases = listOf<Pair<GetRVariableIfc, kotlin.reflect.KClass<*>>>(
            Metalog2P(0.0, 1.0) to Metalog2PRV::class,
            Metalog3P(0.0, 1.0, 0.1) to Metalog3PRV::class,
            Metalog4P(0.0, 1.0, 0.1, 0.2) to Metalog4PRV::class,
            Metalog5P(0.0, 1.0, 0.1, 0.2, 0.0) to Metalog5PRV::class,
            Metalog6P(0.0, 1.0, 0.1, 0.2, 0.0, 0.0) to Metalog6PRV::class,
        )
        for ((distribution, expected) in cases) {
            val rv = distribution.randomVariable(1)
            assertEquals(expected, rv::class, "$distribution handed back a ${rv::class.simpleName}")
        }
    }

    @Test
    fun theRandomVariableFromADistributionCarriesItsParameters() {
        val distribution = Metalog3P(5.0, 1.5, 0.2, lowerBound = 1.0)
        val rv = distribution.randomVariable(2)
        assertNearly(5.0, rv.a1)
        assertNearly(1.5, rv.a2)
        assertNearly(0.2, rv.a3)
        assertNearly(1.0, rv.lowerBound)
        assertEquals(MetalogBoundedness.LowerBounded, rv.boundedness)
    }

    // -------- validation reaches every construction path --------

    @Test
    fun theConstructorRejectsInfeasibleCoefficients() {
        assertFailsWith<IllegalArgumentException>("a negative scale") { Metalog2PRV(0.0, -1.0) }
        assertFailsWith<IllegalArgumentException>("a zero scale") { Metalog2PRV(0.0, 0.0) }
        assertFailsWith<IllegalArgumentException>("too much skewness") { Metalog3PRV(0.0, 1.0, 2.0) }
        assertFailsWith<IllegalArgumentException>("a non-monotone six term set") {
            Metalog6PRV(0.0, 1.0, 0.0, 0.0, 0.0, -40.0)
        }
    }

    @Test
    fun theConstructorRejectsInvalidBounds() {
        assertFailsWith<IllegalArgumentException>("reversed") {
            Metalog2PRV(0.0, 1.0, lowerBound = 5.0, upperBound = 1.0)
        }
        assertFailsWith<IllegalArgumentException>("equal") {
            Metalog2PRV(0.0, 1.0, lowerBound = 2.0, upperBound = 2.0)
        }
        assertFailsWith<IllegalArgumentException>("not a number") {
            Metalog2PRV(0.0, 1.0, lowerBound = Double.NaN)
        }
    }

    @Test
    fun creatingFromMutatedParametersRejectsAnInfeasibleSet() {
        // This is the path a scripted parameter change takes, so the rejection has to happen here
        // rather than only in a direct constructor call.
        val parameters = Metalog2PRVParameters()
        parameters.changeDoubleParameter("a2", -1.0)
        assertFailsWith<IllegalArgumentException> { parameters.createRVariable() }
        val threeTerm = Metalog3PRVParameters()
        threeTerm.changeDoubleParameter("a3", 9.0)
        assertFailsWith<IllegalArgumentException> { threeTerm.createRVariable() }
    }

    @Test
    fun creatingADistributionFromMutatedParametersAlsoRejects() {
        val parameters = Metalog3PRVParameters()
        parameters.changeDoubleParameter("a3", 9.0)
        assertFailsWith<IllegalArgumentException> {
            (parameters as CreateDistributionIfc).createDistribution()
        }
    }

    @Test
    fun anInstanceRevalidatesRatherThanTrustingItsSource() {
        // Nothing can make an instance invalid today, but the call must not bypass the check, so
        // that it stays safe if the constructor gains state later.
        val rv = Metalog3PRV(1.0, 2.0, 0.3, streamNum = 5)
        val copy = rv.instance(6) as Metalog3PRV
        assertTrue(copy.coefficients().contentEquals(rv.coefficients()))
    }

    @Test
    fun everyAritysDefaultParametersAreFeasible() {
        // The parameter objects are mutable and unvalidated by design, so the defaults have to
        // describe a valid metalog or creating from untouched defaults would throw.
        for (parameters in allParameterTypes()) {
            val rv = parameters.createRVariable()
            assertTrue(rv.value.isFinite(), "${parameters.rvClassName}: a default variate was not finite")
            val distribution = (parameters as CreateDistributionIfc).createDistribution()
            assertTrue(distribution is MetalogDistribution, parameters.rvClassName)
        }
    }

    // -------- scalar parameters participate everywhere --------

    @Test
    fun theDoubleMapContainsEveryCoefficientAndBothBounds() {
        // The motivation for one class per term count. An array-valued parameter is skipped by this
        // map entirely, which would leave a fitted metalog reporting no parameters at all.
        val parameters = Metalog4PRV(1.0, 2.0, 0.3, 0.4, lowerBound = -1.0).parameters
        val map = parameters.asDoubleMap()
        assertEquals(6, map.size, "the map was $map")
        assertNearly(1.0, map.getValue("a1"))
        assertNearly(2.0, map.getValue("a2"))
        assertNearly(0.3, map.getValue("a3"))
        assertNearly(0.4, map.getValue("a4"))
        assertNearly(-1.0, map.getValue("lowerBound"))
        assertTrue(map.getValue("upperBound") == Double.POSITIVE_INFINITY)
    }

    @Test
    fun theParameterCountMatchesTheArityPlusTwoBounds() {
        val expected = mapOf(2 to 4, 3 to 5, 4 to 6, 5 to 7, 6 to 8)
        for ((index, parameters) in allParameterTypes().withIndex()) {
            val terms = index + 2
            assertEquals(
                expected.getValue(terms), parameters.asDoubleMap().size,
                "${parameters.rvClassName} reported the wrong parameter count",
            )
        }
    }

    @Test
    fun parameterDataIsExtractedForPersistence() {
        // The database path walks only the double and integer maps, so array-valued parameters
        // never reach it. Every metalog parameter must appear here.
        val parameters = Metalog3PRV(1.0, 2.0, 0.3).parameters
        val rows = parameters.extractParameterData(42, "testRV")
        assertEquals(5, rows.size, "the extracted rows were $rows")
        val names = rows.map { it.paramName }.toSet()
        assertTrue(names.containsAll(setOf("a1", "a2", "a3", "lowerBound", "upperBound")), "$names")
    }

    @Test
    fun everyParameterIsReachableThroughTheGenericChanger() {
        // The parameter setter used for designed experiments changes parameters by name through a
        // double-valued call, which returns false for an array-valued parameter.
        val parameters = Metalog5PRVParameters()
        for (name in listOf("a1", "a2", "a3", "a4", "a5", "lowerBound", "upperBound")) {
            assertTrue(
                parameters.changeParameter(name, 1.0),
                "the parameter $name could not be changed through the generic call",
            )
        }
        assertFalse(parameters.changeParameter("notAParameter", 1.0))
    }

    @Test
    fun noMetalogParameterSetUsesAnArrayValuedParameter() {
        for (parameters in allParameterTypes()) {
            assertFalse(
                parameters.hasDoubleArrayParameter(),
                "${parameters.rvClassName} declared an array-valued parameter",
            )
            assertTrue(parameters.hasDoubleParameters(), parameters.rvClassName)
        }
    }

    // -------- parameter round trips --------

    @Test
    fun parametersRoundTripThroughCreation() {
        for (rv in allArities(streamNum = 4)) {
            val parameters = rv.parameters
            val rebuilt = parameters.createRVariable(4) as ParameterizedRV
            assertEquals(
                parameters.asDoubleMap(), rebuilt.parameters.asDoubleMap(),
                "${rv::class.simpleName} did not round trip",
            )
        }
    }

    @Test
    fun boundsRoundTripIncludingTheirInfinities() {
        val rv = Metalog3PRV(0.0, 1.0, 0.1, lowerBound = 2.0)
        val rebuilt = rv.parameters.createRVariable(1) as Metalog3PRV
        assertNearly(2.0, rebuilt.lowerBound)
        assertTrue(rebuilt.upperBound == Double.POSITIVE_INFINITY)
        assertEquals(MetalogBoundedness.LowerBounded, rebuilt.boundedness)
    }

    @Test
    fun theRandomVariableAndItsDistributionAgreeOnTheirParameters() {
        val parameters = Metalog4PRVParameters()
        parameters.changeDoubleParameter("a1", 3.0)
        parameters.changeDoubleParameter("a2", 1.5)
        parameters.changeDoubleParameter("a3", 0.2)
        parameters.changeDoubleParameter("a4", 0.4)
        parameters.changeDoubleParameter("lowerBound", 0.0)
        val rv = parameters.createRVariable(1) as Metalog4PRV
        val distribution = (parameters as CreateDistributionIfc).createDistribution() as Metalog4P
        assertNearly(distribution.a1, rv.a1)
        assertNearly(distribution.a4, rv.a4)
        assertNearly(distribution.lowerBound, rv.lowerBound)
        // Generating from the random variable must land where the distribution says it should.
        val sample = DoubleArray(2_000) { rv.value }
        sample.sort()
        assertNearly(
            distribution.invCDF(0.5), sample[1_000], relTol = 0.1, absTol = 0.2,
            message = "median:",
        )
    }

    // -------- type registration --------

    @Test
    fun everyArityIsRegisteredAsARandomVariableType() {
        val types = listOf(
            RVType.Metalog2P, RVType.Metalog3P, RVType.Metalog4P,
            RVType.Metalog5P, RVType.Metalog6P,
        )
        val classes = listOf(
            Metalog2PRV::class, Metalog3PRV::class, Metalog4PRV::class,
            Metalog5PRV::class, Metalog6PRV::class,
        )
        for ((index, type) in types.withIndex()) {
            assertEquals(classes[index], type.parametrizedRVClass, "$type")
            assertEquals(type, RVType.classToTypeMap[classes[index]], "${classes[index]}")
            assertTrue(RVType.RVTYPE_SET.contains(type), "$type was missing from the type set")
        }
    }

    @Test
    fun eachRandomVariableReportsItsOwnType() {
        assertEquals(RVType.Metalog2P, Metalog2PRV(0.0, 1.0).rvType)
        assertEquals(RVType.Metalog3P, Metalog3PRV(0.0, 1.0, 0.1).rvType)
        assertEquals(RVType.Metalog4P, Metalog4PRV(0.0, 1.0, 0.1, 0.2).rvType)
        assertEquals(RVType.Metalog5P, Metalog5PRV(0.0, 1.0, 0.1, 0.2, 0.0).rvType)
        assertEquals(RVType.Metalog6P, Metalog6PRV(0.0, 1.0, 0.1, 0.2, 0.0, 0.0).rvType)
    }

    @Test
    fun eachDistributionReportsItsOwnType() {
        // A distribution delegates the type interface, so it exposes the parameter prototype and
        // the random variable class rather than the type constant directly.
        assertEquals(RVType.Metalog2P, Metalog2P(0.0, 1.0).rvParameters.rvType)
        assertEquals(RVType.Metalog3P, Metalog3P(0.0, 1.0, 0.1).rvParameters.rvType)
        assertEquals(RVType.Metalog6P, Metalog6P(0.0, 1.0, 0.1, 0.2, 0.0, 0.0).rvParameters.rvType)
        assertEquals(Metalog3PRV::class, Metalog3P(0.0, 1.0, 0.1).parametrizedRVClass)
    }

    @Test
    fun theTypeHandsBackFreshParametersEachTime() {
        val first = RVType.Metalog3P.rvParameters
        first.changeDoubleParameter("a1", 99.0)
        val second = RVType.Metalog3P.rvParameters
        assertNearly(0.0, second.doubleParameter("a1"))
    }

    // -------- reachability from the fitting subsystem --------

    @Test
    fun theModelerBuildsEveryArityFromItsParameters() {
        for ((index, parameters) in allParameterTypes().withIndex()) {
            val terms = index + 2
            val distribution = PDFModeler.createDistribution(parameters)
            assertTrue(
                distribution is MetalogDistribution,
                "$terms terms produced $distribution rather than a metalog",
            )
            assertEquals(terms, (distribution as MetalogDistribution).numTerms)
        }
    }

    @Test
    fun theModelerCarriesCoefficientsAndBoundsThrough() {
        val parameters = Metalog3PRVParameters()
        parameters.changeDoubleParameter("a1", 4.0)
        parameters.changeDoubleParameter("a2", 1.25)
        parameters.changeDoubleParameter("a3", 0.3)
        parameters.changeDoubleParameter("lowerBound", 1.0)
        val distribution = PDFModeler.createDistribution(parameters) as Metalog3P
        assertNearly(4.0, distribution.a1)
        assertNearly(1.25, distribution.a2)
        assertNearly(0.3, distribution.a3)
        assertNearly(1.0, distribution.lowerBound)
        assertEquals(MetalogBoundedness.LowerBounded, distribution.boundedness)
    }

    // -------- description --------

    @Test
    fun theDescriptionNamesEveryCoefficientAndBothBounds() {
        val text = Metalog3PRV(1.0, 2.0, 0.3, lowerBound = 0.5).toString()
        assertTrue(text.contains("Metalog3PRV"), text)
        assertTrue(text.contains("a1=1.0"), text)
        assertTrue(text.contains("a2=2.0"), text)
        assertTrue(text.contains("a3=0.3"), text)
        assertTrue(text.contains("lowerBound=0.5"), text)
        assertTrue(text.contains("upperBound=Infinity"), text)
    }

    @Test
    fun theCoefficientCopyIsDefensive() {
        val rv = Metalog3PRV(1.0, 2.0, 0.3)
        val taken = rv.coefficients()
        taken[0] = 999.0
        assertNearly(1.0, rv.coefficients()[0])
    }
}
