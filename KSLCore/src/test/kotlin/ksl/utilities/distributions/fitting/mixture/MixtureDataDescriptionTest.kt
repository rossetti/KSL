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

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.NormalRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  What the workflow says about a sample before it fits anything to it.
 *
 *  The gate is the part worth pinning. It **warns and proceeds; it never refuses**, and that is
 *  structural rather than a matter of policy — there is no refusing value to return. Refusing would
 *  be wrong in exactly the case the method is for: a mixture on unimodal data with a known
 *  mechanism, where the analyst knows about the mechanism and the tool does not.
 */
class MixtureDataDescriptionTest {

    private fun unimodal(n: Int, stream: Int): DoubleArray =
        NormalRV(0.0, 1.0, streamNum = stream).sample(n)

    private fun bimodal(n: Int, stream: Int): DoubleArray =
        NormalRV(0.0, 1.0, streamNum = stream).sample(n / 2) +
                NormalRV(6.0, 1.0, streamNum = stream + 1).sample(n - n / 2)

    @Test
    fun `the description reports the structural facts of the sample`() {
        val data = bimodal(400, 201)
        val modeler = MixtureModeler(data)
        val description = modeler.describe(numBootstrapSamples = 30)
        assertEquals(data.size, description.numObservations)
        assertEquals(modeler.certificate.numDistinctValues, description.numDistinctValues)
        assertEquals(
            modeler.certificate.maximumFeasibleGroups,
            description.maximumFeasibleComponents,
            "the ceiling must be the certificate's, not a second opinion"
        )
        assertEquals(data.size, description.statistics.count.toInt())
    }

    @Test
    fun `a clearly bimodal sample proceeds without a warning`() {
        val description = MixtureModeler(bimodal(400, 211)).describe(numBootstrapSamples = 40)
        assertNull(
            description.warningOrNull(hasMechanism = false),
            "visible structure is a warrant on its own"
        )
        assertEquals(EntryGuidance.PROCEED, description.entryGuidance(hasMechanism = false))
        assertTrue(description.modality.apparentModes >= 1)
    }

    @Test
    fun `a normal sample with no mechanism is warned about but not refused`() {
        val description = MixtureModeler(unimodal(400, 221)).describe(numBootstrapSamples = 40)
        val warning = description.warningOrNull(hasMechanism = false)
        assertNotNull(warning, "neither warrant is present, so there is something to say")
        assertEquals(
            EntryGuidance.PROCEED_WITH_WARNING,
            description.entryGuidance(hasMechanism = false)
        )
        assertTrue(
            warning.contains("proceed"),
            "the gate must read as advice rather than as a refusal: $warning"
        )
    }

    @Test
    fun `a claimed mechanism removes the warning`() {
        val description = MixtureModeler(unimodal(400, 231)).describe(numBootstrapSamples = 40)
        assertNull(
            description.warningOrNull(hasMechanism = true),
            "a mechanism the analyst knows about is a warrant the tool cannot see"
        )
        assertTrue(description.entryGuidance(hasMechanism = true) != EntryGuidance.PROCEED_WITH_WARNING)
    }

    @Test
    fun `no guidance value refuses`() {
        // Structural, not a policy the caller has to remember: the enum has no refusing member,
        // so no code path can produce one.
        assertEquals(
            setOf("PROCEED", "PROCEED_WITH_WEAK_IDENTIFICATION", "PROCEED_WITH_WARNING"),
            EntryGuidance.entries.map { it.name }.toSet()
        )
    }

    @Test
    fun `a sample too small to support several components reports weak identification`() {
        // Twenty observations cannot make many groups at the default minimum group size, so the
        // ceiling is low and the analyst should know before hypothesising.
        val small = DoubleArray(20) { it.toDouble() + NormalRV(0.0, 0.01, streamNum = 241).value }
        val modeler = MixtureModeler(small)
        val description = modeler.describe(numBootstrapSamples = 20)
        assertTrue(
            description.maximumFeasibleComponents < 5,
            "a sample this small cannot support many components"
        )
        assertTrue(
            description.entryGuidance(hasMechanism = true) != EntryGuidance.PROCEED,
            "a low ceiling is worth flagging even when a mechanism is claimed"
        )
    }

    @Test
    fun `the characterisation is available as properties, following the library`() {
        val modeler = MixtureModeler(bimodal(300, 251))
        // properties for what is cheap, a method for what bootstraps -- PDFModeler's own split
        assertNotNull(modeler.histogram)
        assertNotNull(modeler.statistics)
        assertTrue(modeler.maximumFeasibleComponents >= 1)
        assertEquals(300, modeler.statistics.count.toInt())
    }

    @Test
    fun `the description is reproducible from a stream number`() {
        // Two providers of their own, and components close enough that the p-value lands strictly
        // inside its range. Both matter. An earlier version of this test shared the library's
        // provider and used well-separated components; it passed while the bootstrap underneath was
        // not reproducible at all, because the p-value was pinned at zero either way.
        val data = NormalRV(0.0, 1.0, streamNum = 261).sample(150) +
                NormalRV(1.7, 1.0, streamNum = 262).sample(150)
        val a = MixtureModeler(data).describe(
            numBootstrapSamples = 60, streamNumber = 1, streamProvider = RNStreamProvider()
        )
        val b = MixtureModeler(data).describe(
            numBootstrapSamples = 60, streamNumber = 1, streamProvider = RNStreamProvider()
        )
        assertTrue(
            a.modality.unimodalityTest.pValue > 0.0 && a.modality.unimodalityTest.pValue < 1.0,
            "a saturated p-value would make this pass without testing anything; " +
                    "p = ${a.modality.unimodalityTest.pValue}"
        )
        assertEquals(a.modality.unimodalityTest.pValue, b.modality.unimodalityTest.pValue)
        assertEquals(
            a.modality.unimodalityTest.criticalBandwidth,
            b.modality.unimodalityTest.criticalBandwidth
        )
    }

    @Test
    fun `the printed description leads with the ceiling and the modality caveat`() {
        val text = MixtureModeler(bimodal(300, 271)).describe(numBootstrapSamples = 25).toString()
        assertTrue(text.contains("Before fitting anything"))
        assertTrue(
            text.contains("components can be fitted to a sample this size"),
            "the structural ceiling belongs in front of the analyst before any fit"
        )
        assertTrue(
            text.contains("bound the number of components from below"),
            "modes are a floor on the count, and the description must say so"
        )
    }
}
