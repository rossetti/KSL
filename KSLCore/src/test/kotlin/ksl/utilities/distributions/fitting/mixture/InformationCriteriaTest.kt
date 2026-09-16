package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.fitting.mixture.scoring.MixtureAICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  Guards the information criteria against their own definitions.
 *
 *  Every check the project had before was a *relative* one — values ordered candidates plausibly
 *  and moved sensibly with sample size — and a criterion that did not penalise complexity at all
 *  passed all of them for the length of a full experiment. These assert the arithmetic instead.
 */
class InformationCriteriaTest {

    private val data = receptionDeskData()

    /**
     *  Pins the project's criterion to the definition, independently of the library.
     */
    @Test
    fun aicIsMinusTwoLogLikelihoodPlusTwoParameters() {
        val results = MixtureModeler(data).fit(numComponentsRange = 1..4)
        val criterion = MixtureAICCriterion()
        for (ranked in results.results) {
            val candidate = ranked.candidate
            val ll = candidate.logLikelihood(results.observations)
            if (!ll.isUsable) continue
            val expected = -2.0 * ll.value + 2.0 * candidate.numFreeParameters
            val actual = criterion.evaluate(candidate, results.observations)
            assertTrue(
                abs(actual.value - expected) < 1.0e-9,
                "k=${candidate.numComponents}: expected $expected but was ${actual.value}"
            )
        }
    }

    /** The same for BIC, which was the control that exposed the AIC defect. */
    @Test
    fun bicIsMinusTwoLogLikelihoodPlusParametersTimesLogN() {
        val results = MixtureModeler(data).fit(numComponentsRange = 1..4)
        val criterion = MixtureBICCriterion()
        val logN = ln(results.observations.size.toDouble())
        for (ranked in results.results) {
            val candidate = ranked.candidate
            val ll = candidate.logLikelihood(results.observations)
            if (!ll.isUsable) continue
            val expected = -2.0 * ll.value + candidate.numFreeParameters * logN
            val actual = criterion.evaluate(candidate, results.observations)
            assertTrue(
                abs(actual.value - expected) < 1.0e-9,
                "k=${candidate.numComponents}: expected $expected but was ${actual.value}"
            )
        }
    }
}
