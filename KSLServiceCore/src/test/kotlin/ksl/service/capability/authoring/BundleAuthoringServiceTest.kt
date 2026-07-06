/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.service.capability.authoring

import ksl.app.bundle.BundleLoader
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the headless bundle-authoring capability: discover a builders JAR's models as annotatable
 * candidates, apply an LLM-style authoring payload (catalog + classification + identity), and assemble
 * a bundle whose loaded manifest/catalog carry the authored data — plus the validate-without-write and
 * bad-identity paths.
 */
class BundleAuthoringServiceTest {

    private val service = BundleAuthoringService()

    private fun buildersJar(): Pair<Path, Path> {
        val dir = Files.createTempDirectory("bundle-authoring")
        return dir to ManifestBundleFixtures.buildersJar(dir, "mm1-builders", MM1ModelBuilder::class.java)
    }

    @Test
    fun `candidates expose a model's annotatable inputs and outputs`() {
        val (_, jar) = buildersJar()
        val candidates = service.candidates(jar)
        assertEquals(1, candidates.models.size, "one builder discovered")
        val model = candidates.models.first()
        assertTrue(model.inputs.isNotEmpty(), "MM1 should expose inputs (controls / RV parameters)")
        assertTrue(model.outputs.isNotEmpty(), "MM1 should expose response outputs")
        // Inputs carry the kind + context an LLM needs to name them.
        assertTrue(model.inputs.all { it.key.isNotBlank() && it.kind.isNotBlank() })
    }

    @Test
    fun `assemble writes a bundle whose manifest and catalog carry the authored metadata`() {
        val (dir, jar) = buildersJar()
        val model = service.candidates(jar).models.first()
        val inputKey = model.inputs.first().key
        val outputName = model.outputs.first()

        val request = BundleAuthoringRequest(
            identity = BundleIdentity(bundleId = "edu.test.mm1", displayName = "Test M/M/1"),
            models = listOf(
                ModelAuthoring(
                    builderClass = model.builderClass,
                    displayName = "M/M/1 Queue",
                    supportedApps = listOf("SINGLE", "SCENARIO"),
                    catalog = CatalogAuthoring(
                        inputs = listOf(InputAnnotation(inputKey, displayName = "Authored Input", unit = "u")),
                        outputs = listOf(OutputAnnotation(outputName, displayName = "Authored Output", unit = "min")),
                    ),
                ),
            ),
        )
        val outcome = service.assemble(jar, request, dir.resolve("mm1.jar"))
        assertEquals(0, outcome.report.errorCount, "clean authoring should assemble: ${outcome.report.findings}")
        assertNotNull(outcome.written, "a bundle should be written")

        // Load it back: the identity + the authored catalog display name survive the round trip.
        val loaded = BundleLoader.loadJar(outcome.written!!)
        try {
            val bundle = loaded.first()
            assertEquals("edu.test.mm1", bundle.bundle.bundleId)
            val catalog = bundle.descriptorFor(model.defaultModelId).catalog
            assertNotNull(catalog, "the bundle should carry the authored catalog")
            assertTrue(
                catalog.nominatedInputs.any { it.displayName == "Authored Input" },
                "the authored input display name should be in the catalog; got ${catalog.nominatedInputs}",
            )
            assertTrue(catalog.nominatedOutputs.any { it.displayName == "Authored Output" })
        } finally {
            loaded.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `preview reports a blank bundleId as an error without writing anything`() {
        val (dir, jar) = buildersJar()
        val model = service.candidates(jar).models.first()
        val request = BundleAuthoringRequest(
            identity = BundleIdentity(bundleId = ""),   // invalid
            models = listOf(ModelAuthoring(builderClass = model.builderClass)),
        )
        val report = service.preview(jar, request)
        assertTrue(report.errorCount > 0, "a blank bundleId should be an error; got ${report.findings}")
        // Nothing was written by preview (only the temp validation jar, which is cleaned up).
        val jars = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".jar") }.toList() }
        assertEquals(setOf(jar.fileName.toString()), jars.map { it.fileName.toString() }.toSet(),
            "preview must not write a bundle; found $jars")
    }
}
