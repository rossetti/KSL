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

import ksl.utilities.distributions.Normal
import ksl.utilities.random.rvariable.RVType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Whether a sample can settle the number of components at all.
 *
 *  The verdict is calibrated against the designed experiment: over 11,520 attempts the component
 *  count was recovered on 4.4% of those this rates INSUFFICIENT, 39.3% of those it rates MARGINAL
 *  and 69.7% of those it rates AMPLE. What is pinned here is that the thresholds still read that
 *  curve, that separating the components moves the verdict the right way, and that the caveats
 *  the plan requires are actually carried rather than assumed.
 */
class CountAdequacyTest {

    private val types = listOf(RVType.Normal, RVType.Normal)

    private fun pair(separation: Double, n: Int): CountAdequacy? = CountAdequacy.of(
        doubleArrayOf(0.5, 0.5),
        listOf(Normal(0.0, 1.0), Normal(separation, 1.0)),
        types,
        n
    )

    @Test
    fun `well separated components on a large sample are ample`() {
        val a = pair(4.0, 2000)
        assertNotNull(a)
        assertEquals(AdequacyVerdict.AMPLE, a.verdict)
        assertEquals(2, a.hypothesisedCount)
        assertEquals(2000, a.sampleSize)
    }

    @Test
    fun `barely separated components on a small sample are insufficient`() {
        val a = pair(0.5, 50)
        assertNotNull(a)
        assertEquals(AdequacyVerdict.INSUFFICIENT, a.verdict)
    }

    @Test
    fun `the verdict improves with the sample size at a fixed separation`() {
        // The whole point of the quantity: the same components become answerable with more data.
        // Anything that broke the ratio would leave the verdict flat here.
        val ordered = listOf(pair(2.0, 20)!!, pair(2.0, 500)!!, pair(2.0, 20000)!!)
        for (i in 1 until ordered.size) {
            assertTrue(
                ordered[i].verdict >= ordered[i - 1].verdict,
                "the verdict went backwards as the sample grew: " +
                        "${ordered.map { it.verdict }}"
            )
        }
        assertEquals(AdequacyVerdict.INSUFFICIENT, ordered.first().verdict)
        assertEquals(AdequacyVerdict.AMPLE, ordered.last().verdict)
    }

    @Test
    fun `the verdict improves as the components separate at a fixed sample size`() {
        val close = pair(0.75, 300)!!
        val far = pair(4.0, 300)!!
        assertTrue(
            far.separability > close.separability,
            "separating the components must raise the separability"
        )
        assertTrue(far.adequacyRatio > close.adequacyRatio)
        assertTrue(far.verdict >= close.verdict)
    }

    @Test
    fun `the thresholds are the ones the measured curve supports`() {
        // Not arbitrary: recovery is 11.9% at a ratio of one and about 72% by ten, flat after.
        // A change to either without a change to the curve would be a change of claim.
        assertEquals(1.0, CountAdequacy.insufficientBelow)
        assertEquals(10.0, CountAdequacy.ampleAtOrAbove)
        assertEquals(4.2, CountAdequacy.evenOddsConstant)
        assertEquals(AdequacyVerdict.INSUFFICIENT, CountAdequacy.verdictFor(0.999))
        assertEquals(AdequacyVerdict.MARGINAL, CountAdequacy.verdictFor(1.0))
        assertEquals(AdequacyVerdict.MARGINAL, CountAdequacy.verdictFor(9.999))
        assertEquals(AdequacyVerdict.AMPLE, CountAdequacy.verdictFor(10.0))
    }

    @Test
    fun `the even-odds size follows the measured rule`() {
        val a = pair(2.0, 300)!!
        val expected = Math.ceil(
            CountAdequacy.evenOddsConstant / (a.separability * a.separability)
        ).toInt()
        assertEquals(expected, a.sizeForEvenOdds)
    }

    @Test
    fun `a single component has no count to settle`() {
        assertNull(
            CountAdequacy.of(
                doubleArrayOf(1.0), listOf(Normal(0.0, 1.0)), listOf(RVType.Normal), 500
            ),
            "one component cannot be more or less adequate"
        )
    }

    @Test
    fun `a nonsense sample size is refused`() {
        assertFailsWith<IllegalArgumentException> { pair(2.0, 0) }
    }

    @Test
    fun `every verdict carries the four caveats and never overclaims`() {
        // The plan requires four statements wherever this is displayed. The one that matters most
        // is that AMPLE is not a guarantee, so it is checked in the prose and not only in the list.
        for (a in listOf(pair(0.5, 50)!!, pair(2.0, 300)!!, pair(4.0, 2000)!!)) {
            assertEquals(4, a.caveats().size, "all four caveats must be present")
            assertTrue(a.caveats().any { it.contains("order of magnitude") })
            assertTrue(a.caveats().any { it.contains("rule of thumb") })
            // This caveat has been corrected twice and the second correction is the reason the
            // numbers are gone. It first said recovery saturates near 77% however much data is
            // supplied; splitting the designed experiment by whether the catalog can represent the
            // truth showed that to be an average of a curve reaching 98% and one falling to 28%.
            // Both versions reported a result about the method over synthetic truths, which is not
            // evidence about the sample an analyst is holding, so the caveat now keeps only the
            // part they can act on: the direction turns on something they cannot observe.
            assertTrue(
                a.caveats().any {
                    it.contains("not that the count is right") && it.contains("cannot be read off")
                },
                "AMPLE must be disclaimed without importing a finding: ${a.caveats()}"
            )
            assertTrue(a.caveats().none { c -> c.any(Char::isDigit) }, "no caveat may quote a rate")
            assertTrue(a.caveats().any { it.contains("floor") })
        }
        val ample = pair(4.0, 2000)!!
        assertTrue(
            ample.explain().contains("does not make the count right"),
            "an ample verdict must not read as a guarantee: ${ample.explain()}"
        )
    }

    @Test
    fun `the modeler reports adequacy for the count it recommends`() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponents = 3)
        val adequacy = results.countAdequacy()
        assertNotNull(adequacy, "a three-component fit has a count to assess")
        assertEquals(3, adequacy.hypothesisedCount)
        assertEquals(results.observations.size, adequacy.sampleSize)
        assertTrue(adequacy.separability > 0.0)
    }

    @Test
    fun `a one-component recommendation reports no adequacy`() {
        val results = MixtureModeler(receptionDeskData()).fit(numComponents = 1)
        assertNull(
            results.countAdequacy(),
            "there is no component count to settle when one component was fitted"
        )
    }
}
