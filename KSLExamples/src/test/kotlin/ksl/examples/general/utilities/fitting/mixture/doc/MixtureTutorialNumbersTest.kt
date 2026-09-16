package ksl.examples.general.utilities.fitting.mixture.doc

import ksl.examples.general.utilities.fitting.mixture.receptionDeskComponents
import ksl.examples.general.utilities.fitting.mixture.receptionDeskData
import ksl.examples.general.utilities.fitting.mixture.receptionDeskFamilies
import ksl.examples.general.utilities.fitting.mixture.receptionDeskHoldOut
import ksl.examples.general.utilities.fitting.mixture.receptionDeskTruth
import ksl.examples.general.utilities.fitting.mixture.receptionDeskWeights
import ksl.utilities.distributions.fitting.mixture.MixtureModeler
import ksl.utilities.distributions.fitting.mixture.MixtureModelingResults
import ksl.utilities.distributions.fitting.mixture.RecoveryMeasures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 *  The tutorial quotes numbers, and a document that quotes numbers goes stale silently.
 *
 *  `MixtureTutorialSnippetsTest` checks that the guide's *code* still exists. This checks that its
 *  *results* are still what it says they are. The two failures look nothing alike: code drift stops
 *  compiling, whereas a changed default, a corrected estimator or a reordered search leaves every
 *  snippet valid and quietly turns the surrounding prose into fiction. A reader following along and
 *  seeing different numbers has no way to tell which of them is wrong.
 *
 *  Only the deterministic figures are pinned here. The fitting is a function of the shipped data,
 *  so Parts II through VII reproduce exactly. Part VIII bootstraps with `streamNum = 0`, drawing
 *  fresh resamples per run — measured at 61% and 55% on two runs of the same procedure — so its
 *  percentages are presented in the tutorial as two runs rather than as facts, and nothing here
 *  asserts them.
 *
 *  A failure means the tutorial needs new numbers, not that this test needs a wider tolerance.
 *  Widening it to make a failure go away would defeat the entire purpose of having it.
 */
class MixtureTutorialNumbersTest {

    private val results: MixtureModelingResults by lazy {
        MixtureModeler(receptionDeskData()).fit(numComponentsRange = 1..6)
    }

    /** Loose enough for the last printed digit, tight enough to catch a real change. */
    private fun assertClose(expected: Double, actual: Double, what: String, tolerance: Double = 0.001) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance) {
            "the tutorial says $what is $expected; it is now $actual"
        }
    }

    @Test
    @DisplayName("Part II: the fit recommends five components on data that came from three")
    fun theRecommendedComponentCount() {
        assertEquals(5, results.best!!.candidate.numComponents) {
            "Part II is built on the recommendation being 5 rather than the true 3"
        }
        assertEquals(3, receptionDeskComponents.size)
    }

    @Test
    @DisplayName("Part II: the BIC profile, and that k=3 to k=5 buys almost nothing")
    fun theBicProfile() {
        val profile = results.bestByNumComponents().mapValues { it.value.criterionValue.value }
        assertClose(2244.293, profile.getValue(1), "the BIC at k=1")
        assertClose(2124.958, profile.getValue(3), "the BIC at k=3")
        assertClose(2120.756, profile.getValue(5), "the BIC at k=5")

        // The interpretation Part II hangs on these: 119 bought, then 4.2 more.
        assertClose(119.0, profile.getValue(1) - profile.getValue(3), "the gain from k=1 to k=3", 1.0)
        assertClose(4.2, profile.getValue(3) - profile.getValue(5), "the further gain from k=3 to k=5", 0.1)
    }

    @Test
    @DisplayName("Part II: the first component is a gamma that is nearly the true normal")
    fun theGammaStandingInForANormal() {
        val first = results.best!!.candidate.distributions.first()
        // Part II computes these from the printed shape and scale and compares them to Normal(3, 0.36).
        assertClose(3.05, first.mean(), "the first fitted component's mean", 0.01)
        assertClose(0.376, first.variance(), "the first fitted component's variance", 0.01)
        assertClose(3.0, receptionDeskComponents.first().mean(), "the true first component's mean")
        assertClose(0.36, receptionDeskComponents.first().variance(), "the true first component's variance")
    }

    @Test
    @DisplayName("Part V: the density is close and the decomposition is not")
    fun theRecoveryAgainstTheTruth() {
        val mixture = results.best!!.candidate
        val recovery = RecoveryMeasures.compare(
            receptionDeskWeights, receptionDeskComponents, receptionDeskFamilies,
            mixture.weights, mixture.distributions, mixture.components.map { it.rvType }
        )
        assertClose(0.13290, recovery.hellinger, "the Hellinger distance", 0.0001)
        assertClose(0.04064, recovery.kolmogorov, "the Kolmogorov distance", 0.0001)
        assertTrue(!recovery.numComponentsCorrect) {
            "Part V is about the component count being wrong; it is now right"
        }
    }

    @Test
    @DisplayName("Part V: the fitted mean and variance against the truth")
    fun theMomentsPartVQuotes() {
        val fitted = results.best!!.distribution
        assertClose(7.6173, fitted.mean(), "the fitted mean", 0.001)
        assertClose(42.7617, fitted.variance(), "the fitted variance", 0.001)
        assertClose(8.1000, receptionDeskTruth.mean(), "the true mean")
        assertClose(53.4200, receptionDeskTruth.variance(), "the true variance", 0.01)
    }

    @Test
    @DisplayName("Part V: the held-out sample is fully covered, so the average is defined")
    fun theHeldOutEvaluation() {
        val evaluation = results.best!!.candidate.logLikelihood(receptionDeskHoldOut())
        assertEquals(0, evaluation.numZeroDensity) {
            "Part V quotes 0 held-out observations given zero density"
        }
        assertTrue(evaluation.isUsable)
        assertClose(-3.1717, evaluation.value / receptionDeskHoldOut().size, "the average held-out log-likelihood", 0.001)
    }
}
