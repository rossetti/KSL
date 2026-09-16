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

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.random.rvariable.NormalRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Whether the reported density depends on where the cuts fell.
 *
 *  The thresholds behind the reading are calibrated against the designed experiment, so the
 *  readings themselves are asserted here rather than only their ordering. What the calibration
 *  found is encoded in the rule and pinned by test: the **share reassigned** decides and the
 *  movement only vetoes, because the share separates answerable fits from unanswerable ones
 *  better than the distance does, and it costs nothing to compute.
 *
 *  The mechanism these tests protect is the one the first attempt got wrong. Displacing a cut by a
 *  fixed number of observations, or widening each component's estimation window, disturbs a
 *  well-separated fit the *most*, because the observations forced across the boundary are then the
 *  most alien available — a two-observation window turned a clean `Normal` + `Lognormal` fit into a
 *  triangle spanning the gap. Moving the cut a fixed distance along the data axis instead is what
 *  makes a gap read as a gap.
 */
class PartitionStabilityTest {

    private fun separated(n: Int, stream: Int): DoubleArray =
        NormalRV(0.0, 1.0, streamNum = stream).sample(n / 2) +
                NormalRV(12.0, 1.0, streamNum = stream + 1).sample(n - n / 2)

    private fun blended(n: Int, stream: Int): DoubleArray =
        NormalRV(0.0, 1.0, streamNum = stream).sample(n / 2) +
                NormalRV(1.0, 1.0, streamNum = stream + 1).sample(n - n / 2)

    private fun measure(data: DoubleArray): PartitionStability {
        val modeler = MixtureModeler(data)
        val stability = modeler.partitionStability(modeler.fit(numComponents = 2))
        assertNotNull(stability, "a two-component fit has a cut to move")
        assertEquals(0, stability.numUnmeasured, "every displacement here must be measurable")
        return stability
    }

    @Test
    fun `cuts lying in a gap can be moved without anything changing group`() {
        // The invariant the whole diagnostic rests on. Components twelve deviations apart leave an
        // empty stretch between them; a cut anywhere in it partitions the sample identically, so
        // the refit is the baseline and the movement is exactly zero. Anything else here would mean
        // the diagnostic manufactures movement, and every number it reports elsewhere would be that
        // error plus the effect being measured.
        val stability = measure(separated(200, 301))
        assertEquals(
            0.0, stability.largestShareReassigned,
            "no observation should change group when the cut moves within a gap"
        )
        assertTrue(
            stability.hellingerFromBaseline.all { it == 0.0 },
            "an identical partition must give an identical density, but movements were " +
                    "${stability.hellingerFromBaseline}"
        )
    }

    @Test
    fun `a blended sample moves further than a well-separated one`() {
        // Gate E, stated as the property that holds whatever the threshold turns out to be.
        val separatedMovement = measure(separated(200, 311)).maximumMovement!!
        val blendedMovement = measure(blended(200, 321)).maximumMovement!!
        assertTrue(
            blendedMovement > separatedMovement,
            "the blended fit must be the more cut-dependent of the two, but it moved " +
                    "$blendedMovement against $separatedMovement"
        )
    }

    @Test
    fun `a well-separated sample reads as cuts lying in a gap`() {
        val stability = measure(separated(200, 331))
        assertEquals(
            CutDependence.CUTS_LIE_IN_A_GAP, stability.cutDependence,
            "nothing changed group and the density did not move; movement was " +
                    "${stability.maximumMovement}"
        )
        assertTrue(stability.toString().contains("The cuts lie in a gap"))
    }

    @Test
    fun `a blended sample reads as the density following the cuts`() {
        val stability = measure(blended(200, 341))
        assertTrue(
            stability.largestShareReassigned > 0.0,
            "moving a cut through a single blob must reassign observations"
        )
        assertEquals(
            CutDependence.DENSITY_FOLLOWS_THE_CUTS, stability.cutDependence,
            "the components were fitted to arbitrary slices of one blob; " +
                    "${"%.1f".format(100.0 * stability.largestShareReassigned)}% changed group"
        )
    }

    @Test
    fun `the reading rests on the share reassigned, with the movement as a guard`() {
        // The measured finding this rule exists to encode: the share separates better than the
        // movement does, so the share is what decides and the movement only vetoes.
        val gap = PartitionStability(
            baselineNumComponents = 2, numObservations = 1000,
            displacements = listOf(-0.05, 0.05),
            hellingerFromBaseline = listOf(0.001, 0.002),
            numObservationsReassigned = listOf(0, 2)
        )
        assertEquals(CutDependence.CUTS_LIE_IN_A_GAP, gap.cutDependence)

        // Same tiny reassignment, but those few observations moved the density a long way. The
        // guard is what stops that being read as a gap.
        val vetoed = PartitionStability(
            baselineNumComponents = 2, numObservations = 1000,
            displacements = listOf(-0.05, 0.05),
            hellingerFromBaseline = listOf(0.001, 0.4),
            numObservationsReassigned = listOf(0, 2)
        )
        assertEquals(CutDependence.INCONCLUSIVE, vetoed.cutDependence)

        // A large share reassigned, but a small movement. The share decides.
        val follows = PartitionStability(
            baselineNumComponents = 2, numObservations = 1000,
            displacements = listOf(-0.05, 0.05),
            hellingerFromBaseline = listOf(0.01, 0.02),
            numObservationsReassigned = listOf(120, 300)
        )
        assertEquals(CutDependence.DENSITY_FOLLOWS_THE_CUTS, follows.cutDependence)
    }

    @Test
    fun `the middle is reported as having nothing to say`() {
        // Not a mild version of either signature. Over the designed experiment this is nearly
        // three fits in four of those that can be measured (5,217 of 7,128 against the shipped
        // thresholds), and their count recovery is indistinguishable from not having asked.
        val middle = PartitionStability(
            baselineNumComponents = 2, numObservations = 1000,
            displacements = listOf(-0.05, 0.05),
            hellingerFromBaseline = listOf(0.08, 0.11),
            numObservationsReassigned = listOf(40, 80)
        )
        assertEquals(CutDependence.INCONCLUSIVE, middle.cutDependence)
        assertTrue(middle.toString().contains("nothing to add"))
    }

    @Test
    fun `each displacement is applied in both directions`() {
        val data = blended(200, 361)
        val modeler = MixtureModeler(data)
        val stability = modeler.partitionStability(
            modeler.fit(numComponents = 2), displacements = listOf(0.05)
        )
        assertNotNull(stability)
        assertEquals(listOf(-0.05, 0.05), stability.displacements)
        assertEquals(2, stability.hellingerFromBaseline.size)
    }

    @Test
    fun `no alternative fit escapes into the reported object`() {
        // Structural rather than a matter of discipline: nothing the class exposes can carry a
        // fitted density, so no caller can be handed a menu of alternatives to prefer among.
        val forbidden = setOf(
            RankedMixture::class.qualifiedName,
            MixtureCandidate::class.qualifiedName,
            MixtureModelingResults::class.qualifiedName,
            ContinuousDistributionIfc::class.qualifiedName,
            GroupFitResult::class.qualifiedName,
            DataPartition::class.qualifiedName
        )
        val leaked = PartitionStability::class.members
            .filter { member -> forbidden.any { member.returnType.toString().contains(it!!) } }
            .map { it.name }
        assertTrue(leaked.isEmpty(), "these members hand back a fitted mixture: $leaked")
    }

    @Test
    fun `measuring stability leaves the baseline results untouched`() {
        val modeler = MixtureModeler(separated(200, 371))
        val results = modeler.fit(numComponents = 2)
        val countBefore = results.results.size
        val bestBefore = results.best
        val recommendedBefore = results.recommendedNumComponents
        modeler.partitionStability(results)
        assertEquals(countBefore, results.results.size)
        assertTrue(bestBefore === results.best, "the reported fit must be the same object it was")
        assertEquals(recommendedBefore, results.recommendedNumComponents)
    }

    @Test
    fun `an unmeasured displacement is not a movement of zero`() {
        val stability = PartitionStability(
            baselineNumComponents = 2,
            numObservations = 200,
            displacements = listOf(-0.05, 0.05),
            hellingerFromBaseline = listOf(0.001, null),
            numObservationsReassigned = listOf(0, null)
        )
        assertEquals(1, stability.numUnmeasured)
        assertEquals(0.001, stability.maximumMovement)
        assertEquals(
            CutDependence.INCONCLUSIVE, stability.cutDependence,
            "the measured displacement alone looks like a gap, but a signature established on " +
                    "the displacements that happened to work is not established"
        )
        assertTrue(stability.toString().contains("could not be measured"))
    }

    @Test
    fun `the caveat travels with the verdict`() {
        val modeler = MixtureModeler(separated(200, 381))
        val text = modeler.partitionStability(modeler.fit(numComponents = 2)).toString()
        assertTrue(
            text.contains("designed experiment"),
            "the thresholds are measured against one design, and a report must say which"
        )
        assertTrue(
            text.contains("rule of thumb rather than a theorem"),
            "a rule calibrated on one design must not read as a law"
        )
        assertTrue(PartitionStability.thresholdsAreCalibrated)
    }

    @Test
    fun `a single-component fit has no cut to move`() {
        val modeler = MixtureModeler(NormalRV(0.0, 1.0, streamNum = 391).sample(150))
        val results = modeler.fit(1..1)
        assertEquals(1, results.recommendedNumComponents)
        assertNull(
            modeler.partitionStability(results),
            "stability of the cuts is not a question a one-group partition has"
        )
    }

    @Test
    fun `a baseline fitted to other data is refused rather than measured`() {
        val modeler = MixtureModeler(separated(200, 401))
        val other = MixtureModeler(separated(200, 411)).fit(numComponents = 2)
        assertFailsWith<IllegalArgumentException> { modeler.partitionStability(other) }
    }

    @Test
    fun `the displacements must be positive and finite`() {
        val modeler = MixtureModeler(separated(200, 421))
        val results = modeler.fit(numComponents = 2)
        assertFailsWith<IllegalArgumentException> {
            modeler.partitionStability(results, displacements = listOf(-0.1))
        }
        assertFailsWith<IllegalArgumentException> {
            modeler.partitionStability(results, displacements = listOf(0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            modeler.partitionStability(results, displacements = emptyList())
        }
    }

    @Test
    fun `the Hellinger helper reports nothing rather than a number it cannot stand behind`() {
        // The metric discipline this rests on: a distance is reported only when both densities
        // integrate on the grid it was computed on.
        val candidate = MixtureModeler(separated(200, 431)).fit(numComponents = 2).best!!.candidate
        val self = DensityQuadrature.hellinger(
            candidate.weights, candidate.distributions, candidate.weights, candidate.distributions
        )
        assertNotNull(self)
        assertTrue(self < 1.0e-12, "a density against itself is zero away from itself")
        assertFailsWith<IllegalArgumentException>(
            "mismatched weights and components must not be silently measured"
        ) {
            DensityQuadrature.hellinger(
                candidate.weights,
                candidate.distributions,
                DoubleArray(candidate.numComponents + 1) { 0.5 },
                candidate.distributions
            )
        }
    }
}
