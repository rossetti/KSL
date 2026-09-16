package ksl.utilities.distributions.fitting.mixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Covers fitting a component count the caller has chosen rather than searched for.
 *
 *  The behaviour being pinned is that specifying a count must not cost the caller the
 *  single-distribution comparison, must not let a baseline displace what they asked for, and must
 *  not produce warnings that fire unconditionally.
 */
class SpecifiedComponentCountTest {

    private val data = receptionDeskData()

    @Test
    fun recommendsTheCountThatWasAskedFor() {
        val results = MixtureModeler(data).fit(numComponents = 3)
        assertEquals(3, results.recommendedNumComponents)
        assertEquals(3, results.specifiedNumComponents)
        assertTrue(results.numComponentsWasSpecified)
    }

    @Test
    fun keepsTheSingleDistributionComparisonAvailable() {
        val results = MixtureModeler(data).fit(numComponents = 3)
        assertNotNull(results.singleDistribution, "a baseline must be fitted alongside")
        assertEquals(1, results.singleDistribution!!.candidate.numComponents)
        assertNotNull(results.improvementOverSingle, "the comparison must be measured")
        assertFalse(
            results.worthItSummary().contains("no comparison is available"),
            "the comparison must not be reported as unavailable"
        )
    }

    @Test
    fun theBaselineCannotDisplaceTheRequestedCount() {
        // Fit a count the criterion does not prefer, and check the recommendation stays put. On
        // this data BIC ranks k=1 worse than k=2, so the ordering of `results` is not the thing
        // being relied on; `best` must follow the specification.
        val results = MixtureModeler(data).fit(numComponents = 2)
        assertEquals(2, results.recommendedNumComponents)
        assertTrue(
            results.results.any { it.candidate.numComponents == 1 },
            "the baseline must still be present in the ranked list"
        )
    }

    @Test
    fun doesNotWarnAboutRunningOutOfCandidates() {
        val summary = MixtureModeler(data).fit(numComponents = 3).criterionSummary()
        assertFalse(
            summary.contains("ran out of candidates"),
            "a specified count has not run out of anything:\n$summary"
        )
        assertTrue(summary.contains("was specified rather than selected"), summary)
        assertTrue(summary.contains("prefers k ="), summary)
    }

    @Test
    fun aSearchStillWarnsWhenItRunsOutOfCandidates() {
        // The suppression above must be specific to a specified count, not a removal of the
        // warning. A search truncated at a count the criterion is still improving on must say so.
        val summary = MixtureModeler(data).fit(numComponentsRange = 1..2).criterionSummary()
        assertTrue(
            summary.contains("ran out of candidates"),
            "a genuine boundary minimum must still be reported:\n$summary"
        )
    }

    @Test
    fun preferenceRequiresAComparison() {
        // The defect this replaces: the property was true whenever k > 1, so a range that
        // excluded one component reported a preference with nothing to prefer over. The old name,
        // mixtureIsPreferred, did not say preferred to what, which is how it drifted.
        val noBaseline = MixtureModeler(data).fit(numComponentsRange = 3..3)
        assertNull(noBaseline.improvementOverSingle)
        assertFalse(
            noBaseline.mixtureBeatsSingleDistribution,
            "no comparison was made, so nothing can be preferred"
        )
        assertTrue(
            noBaseline.hasMultipleComponents,
            "the structural fact is separate and still true"
        )
    }

    @Test
    fun preferenceIsMeasuredWhenABaselineExists() {
        val results = MixtureModeler(data).fit(numComponents = 3)
        val improvement = results.improvementOverSingle
        assertNotNull(improvement)
        assertEquals(improvement > 0.0, results.mixtureBeatsSingleDistribution)
    }

    @Test
    fun oneComponentIsAllowedAndPrefersNothing() {
        val results = MixtureModeler(data).fit(numComponents = 1)
        assertEquals(1, results.recommendedNumComponents)
        assertFalse(results.hasMultipleComponents)
        assertFalse(results.mixtureBeatsSingleDistribution, "a single distribution cannot beat itself")
        assertEquals(0.0, results.improvementOverSingle)
    }

    @Test
    fun refusesAnInfeasibleCountAndSaysWhy() {
        // Twelve observations cannot make eight groups at the default minimum group size.
        val small = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0)
        val e = assertFailsWith<IllegalArgumentException> {
            MixtureModeler(small).fit(numComponents = 8)
        }
        val message = e.message ?: ""
        assertTrue(message.contains("8 components cannot be fitted"), message)
        assertTrue(message.length > "8 components cannot be fitted to this sample: ".length,
            "the refusal must name the constraint, not just refuse: $message")
    }

    @Test
    fun rejectsANonsenseCount() {
        assertFailsWith<IllegalArgumentException> { MixtureModeler(data).fit(numComponents = 0) }
    }

    @Test
    fun theDoubtsDoNotTreatAgreementAsCorroboration() {
        // With only the specified count and a one-component baseline on offer, agreement among
        // criteria is near-guaranteed and must not be reported as support for the count.
        val summary = MixtureModeler(data).fit(numComponents = 3).summary()
        assertFalse(
            summary.contains("All reported criteria chose the same number"),
            "criteria offered two candidates did not choose:\n$summary"
        )
        assertTrue(summary.contains("specified, not selected"), summary)
    }

    @Test
    fun theSummaryLeadsWithTheCountThatWasAskedFor() {
        // Gate D: the requested count is the headline, and the reader meets it before any
        // criterion opinion. A summary that led with the criterion's preference would restore
        // exactly the interaction this workflow exists to invert.
        val summary = MixtureModeler(data).fit(numComponents = 3).summary()
        val recommended = summary.indexOf("recommended")
        val doubts = summary.indexOf("What should make you doubt this")
        assertTrue(recommended >= 0, "the summary must name the recommendation")
        assertTrue(doubts >= 0, "the summary must carry the doubts section")
        assertTrue(
            recommended < doubts,
            "the requested count must appear before the caveats, not after them"
        )
        assertTrue(
            summary.contains("specified, not selected"),
            "the summary must say the count was supplied rather than chosen"
        )
    }

    @Test
    fun anOrdinarySearchIsUnaffected() {
        val results = MixtureModeler(data).fit(numComponentsRange = 1..6)
        assertNull(results.specifiedNumComponents)
        assertFalse(results.numComponentsWasSpecified)
        // best is still the criterion's choice, and the comparison still works
        assertEquals(results.results.firstOrNull(), results.best)
        assertNotNull(results.improvementOverSingle)
    }
}
