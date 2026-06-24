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

package ksl.app.config

import ksl.app.bundle.ConfigRecipeKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression test against a real, user-provided multi-bundle Scenario document
 * (`Test_Bundles.toml`) — three scenarios spanning two bundles:
 *   1. "M/M/1 Queue"        ksl.examples.mm1 / MM1
 *   2. "Tandem Queue"       edu.uark.ksl.book-examples / TandemQueue
 *   3. "Pallet Work Center" edu.uark.ksl.book-examples / PalletWorkCenter
 *
 * Pins the import wizard's behavior when such a file is imported for one model:
 * summarize lists all three scenarios with their model ids, and importing for
 * PalletWorkCenter (keeping just the matching scenario, retarget on) yields a
 * single-scenario RUN recipe retargeted to the enclosing bundle, with the other
 * two scenarios and their stale bundleRefs dropped.
 */
class RecipeImportRealFileTest {

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/ksl/app/config/Test_Bundles.toml")!!.use { it.readBytes() }

    @Test
    fun `summarize lists all three scenarios and their model ids`() {
        val s = RecipeImport.summarize(fixtureBytes())
        assertTrue(s.detected)
        // Three scenarios across two bundles -> SCENARIO_BATCH as a whole document.
        assertEquals(ConfigRecipeKind.SCENARIO_BATCH, s.kind)
        assertEquals(RecipeImport.Format.TOML, s.format)
        assertEquals(listOf("MM1", "TandemQueue", "PalletWorkCenter"), s.referencedModelIds)
        assertEquals(
            listOf("M/M/1 Queue", "Tandem Queue", "Pallet Work Center"),
            s.scenarios.map { it.name },
        )
        assertEquals("edu.uark.ksl.book-examples", s.scenarios[2].bundleId)
    }

    @Test
    fun `importing for PalletWorkCenter keeps one scenario - a RUN recipe`() {
        val bytes = fixtureBytes()
        // The wizard pre-checks the scenario(s) matching the target model; here that
        // is index 2 only. Simulate the author accepting that default.
        val result = RecipeImport.importForModel(
            bytes = bytes,
            bundleId = "edu.uark.ksl.book-examples",
            modelId = "PalletWorkCenter",
            keepScenarioIndices = setOf(2),
            retarget = true,
        )
        // One kept scenario -> RUN (this is what the Workbench reported, and it is correct).
        assertEquals(ConfigRecipeKind.RUN, result.kind)

        val decoded = RunConfigurationToml.decode(result.bytes.decodeToString())
        assertEquals(listOf("Pallet Work Center"), decoded.scenarios.map { it.name })
        val ref = decoded.scenarios.single().modelReference
        assertIs<ModelReference.ByBundleAndModelId>(ref)
        assertEquals("edu.uark.ksl.book-examples", ref.bundleId)
        assertEquals("PalletWorkCenter", ref.modelId)
        // The MM1 / Tandem scenarios are gone, and the now-unreferenced ksl.examples.mm1
        // bundleRef is pruned; only the enclosing bundle's ref remains (if any).
        assertTrue(decoded.bundleRefs.none { it.bundleId == "ksl.examples.mm1" })
    }
}
