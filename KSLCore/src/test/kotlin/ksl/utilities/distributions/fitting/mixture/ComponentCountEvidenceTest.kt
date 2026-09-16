/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.distributions.fitting.mixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureAICCriterion
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  The evidence an analyst is shown when deciding whether to keep their own component count.
 *
 *  The behaviour that matters most here is negative: **nothing may present the size of a
 *  criterion's lead as evidence that the criterion is right.** Measured across the designed
 *  experiment, when these criteria pick the wrong count the median gap to the truth is about 19,
 *  and only 4.6% of wrong calls sit inside a weak-evidence margin of two. A display that read a
 *  large lead as confidence would mislead an analyst precisely when they should hold their ground.
 */
class ComponentCountEvidenceTest {

    private val data = receptionDeskData()

    /** Words that would turn a firmness display into a reliability claim. */
    private val overclaimingWords = listOf(
        "confidence", "confident", "reliable", "reliability", "certain", "proves", "proof"
    )

    @Test
    fun `the evidence covers every count that was fitted`() {
        val results = MixtureModeler(data).fit(numComponentsRange = 1..5)
        val evidence = results.componentCountEvidence()
        val fitted = results.bestByNumComponents().keys.sorted()
        assertEquals(fitted, evidence.rows.filter { it.isFeasible }.map { it.numComponents })
        assertTrue(evidence.rows.first().marginalGain == null, "the smallest count has no gain")
    }

    @Test
    fun `agreement is reported and is the headline of the prose`() {
        val results = MixtureModeler(data).fit(numComponentsRange = 1..5)
        val evidence = results.componentCountEvidence()
        val prose = evidence.explain()
        assertNotNull(evidence.criterionInterval)
        assertEquals(
            evidence.criterionInterval!!.last - evidence.criterionInterval!!.first + 1,
            evidence.criterionIntervalWidth
        )
        // whichever way it came out, the first thing said is about agreement, not about a margin
        val firstLine = prose.lineSequence().first()
        assertTrue(
            firstLine.contains("criteria chose") || firstLine.contains("criteria disagree"),
            "the prose must lead with agreement, not a margin: $firstLine"
        )
    }

    @Test
    fun `no user-visible string presents the lead as evidence about the truth`() {
        // Gate C. The wording is the deliverable here, so it is asserted rather than reviewed.
        for (range in listOf(1..3, 1..5)) {
            val evidence = MixtureModeler(data).fit(numComponentsRange = range)
                .componentCountEvidence()
            val prose = evidence.explain().lowercase()
            for (word in overclaimingWords) {
                assertFalse(
                    prose.contains(word),
                    "the evidence prose used '$word', which reads the criterion's lead as " +
                            "reliability:\n${evidence.explain()}"
                )
            }
            assertTrue(
                prose.contains("how firmly") && prose.contains("not how likely"),
                "the firmness caveat must be present:\n${evidence.explain()}"
            )
        }
    }

    @Test
    fun `firmness is available and is named for what it measures`() {
        val results = MixtureModeler(data).fit(numComponentsRange = 1..4)
        val evidence = results.componentCountEvidence()
        val withGain = evidence.rows.first { it.marginalGain != null && it.penaltyIncrement != null }
        val firmness = evidence.firmnessAt(withGain.numComponents)
        assertNotNull(firmness)
        assertEquals(withGain.marginalGain!! - withGain.penaltyIncrement!!, firmness, 1.0e-9)
        assertEquals(firmness, withGain.margin)
    }

    @Test
    fun `near indifference is detected and a firm call is not`() {
        val evidence = MixtureModeler(data).fit(numComponentsRange = 1..4).componentCountEvidence()
        val row = evidence.rows.first { it.margin != null }
        val margin = row.margin!!
        // the predicate must follow the number rather than assert a particular data set's outcome
        assertEquals(
            kotlin.math.abs(margin) <= 2.0,
            evidence.criterionWasNearlyIndifferentAt(row.numComponents)
        )
        assertFalse(
            evidence.criterionWasNearlyIndifferentAt(row.numComponents, within = 0.0)
                    && kotlin.math.abs(margin) > 0.0
        )
    }

    @Test
    fun `a supplied count that the criteria did not choose is not reported as wrong`() {
        val results = MixtureModeler(data).fit(numComponents = 3)
        val evidence = results.componentCountEvidence()
        assertEquals(3, evidence.hypothesisedCount)
        val prose = evidence.explain()
        if (3 !in evidence.criterionChoices.values.toSet()) {
            assertTrue(
                prose.contains("That is not evidence against it"),
                "a disagreeing criterion must not be presented as a refutation:\n$prose"
            )
        }
    }

    @Test
    fun `a negative gain is carried and explained rather than suppressed`() {
        // The partition is regenerated at each count rather than split, so the models do not nest
        // and the likelihood can fall. Where that happens the prose must say why.
        val evidence = MixtureModeler(data).fit(numComponentsRange = 1..6).componentCountEvidence()
        val negative = evidence.rows.filter { (it.marginalGain ?: 0.0) < 0.0 }
        if (negative.isNotEmpty()) {
            assertTrue(
                evidence.explain().contains("do not nest"),
                "a negative gain must be explained where it occurs:\n${evidence.explain()}"
            )
        }
        // whether or not this data produces one, the rows must render without error
        assertTrue(evidence.rows.all { it.numComponents > 0 })
    }

    @Test
    fun `Hannan-Quinn is among the reported criteria and sits between AIC and BIC`() {
        val evidence = MixtureModeler(data).fit(numComponentsRange = 1..5).componentCountEvidence()
        val names = evidence.criterionChoices.keys
        assertTrue("HQC" in names, "Hannan-Quinn must be reported: $names")
        assertTrue("AIC" in names && "BIC" in names)
        val row = evidence.rows.first { it.criterionValues.containsKey("HQC") }
        val aic = row.criterionValues["AIC"]!!
        val bic = row.criterionValues["BIC"]!!
        val hqc = row.criterionValues["HQC"]!!
        // all three are -2logL plus a penalty, and 2 <= 2 ln ln n <= ln n for these sample sizes,
        // so the values must order the same way as their penalties
        assertTrue(
            hqc in aic..bic,
            "HQC $hqc must lie between AIC $aic and BIC $bic on the same fit"
        )
    }

    @Test
    fun `the plot draws the criterion's own numbers and carries the caveat beside it`() {
        // The caveat is the deliverable, but it is no longer drawn on the figure: the plot library
        // does not wrap a caption, so it rendered as one long line clipped mid-word. It lives on
        // the object and whoever renders the figure prints it, which mixtureCountEvidence does.
        val evidence = MixtureModeler(data).fit(numComponents = 3).componentCountEvidence()
        val plot = GainVersusPenaltyPlot(evidence)
        assertTrue(plot.caveat.contains("not how likely"), plot.caveat)
        assertTrue(plot.caption.isBlank(), "a drawn caption would be clipped: '${plot.caption}'")
        assertTrue(plot.title.contains("firmly"), plot.title)
        for (word in overclaimingWords) {
            assertFalse(
                (plot.title + " " + plot.caveat).lowercase().contains(word),
                "the plot presented the gap as '$word'"
            )
        }
        // it must actually render; a plot that throws is not a diagnostic
        assertNotNull(plot.buildPlot())
    }

    @Test
    fun `the separation diagnostic compares the plain criterion against the entropy penalised one`() {
        val results = MixtureModeler(data).fit(numComponentsRange = 1..5)
        val separable = results.componentsAreSeparable()
        assertNotNull(separable, "both BIC and ICL-BIC are reported, so the comparison is possible")
        val chosen = results.componentCountAgreement()
        assertEquals(chosen["ICL-BIC"]!! >= chosen["BIC"]!!, separable)
    }

    @Test
    fun `the separation diagnostic reports absence rather than a guess`() {
        // Asked with criteria that cannot answer it, the honest result is null, not a default.
        val results = MixtureModeler(data).fit(numComponentsRange = 1..4)
        assertNull(
            results.componentsAreSeparable(listOf(MixtureAICCriterion())),
            "without both criteria the comparison cannot be made"
        )
    }

    @Test
    fun `an infeasible count appears as infeasible rather than being dropped`() {
        val small = doubleArrayOf(
            1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0,
            11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0
        )
        val results = MixtureModeler(small).fit(numComponentsRange = 1..8)
        val evidence = results.componentCountEvidence()
        val refused = evidence.rows.filter { !it.isFeasible }
        if (results.rejectedGroupCounts.isNotEmpty()) {
            assertTrue(
                refused.isNotEmpty(),
                "a count the data cannot support must be visible, not absent"
            )
            assertTrue(refused.all { it.logLikelihood == null })
        }
    }
}
