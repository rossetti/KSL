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

package ksl.service.capability.run

import ksl.app.config.BundleRef
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.validation.RunConfigurationValidator
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The portable-configuration contract: ONE `RunConfiguration` file must run in the desktop apps
 * AND on the server.
 *
 * The Single and Scenario apps save `modelReference.type = "byBundleAndModelId"`, because they load
 * models from bundle JARs. The server resolves through `RegistryModelProvider`. Until that class
 * declared the bundle-pair capability, every resolution site tested `provider is BundleModelProvider`
 * — a concrete class — and rejected the app's own saved file with
 * "requires a BundleModelProvider; got RegistryModelProvider", even though the registry could
 * answer the lookup perfectly well. These tests pin the fix: the app-native form resolves on the
 * server, and the server-native `byProviderId` form keeps working.
 */
class PortableModelReferenceTest {

    private val bundleId = "ksl.examples.mm1"
    private val modelId = "MM1"

    /** Assembles the MM1 manifest bundle and drops its JAR into [into] (a watched bundle dir). */
    private fun dropMm1(into: Path): Path {
        val build = Files.createTempDirectory("mm1-build")
        val src = ManifestBundleFixtures.assembleManifestBundle(
            build, "mm1", bundleId, MM1ModelBuilder::class.java,
        )
        return Files.copy(src, into.resolve("mm1.jar"))
    }

    private fun <T> withRegistryProvider(block: (RegistryModelProvider) -> T): T {
        val dir = Files.createTempDirectory("ksl-portable-bundles")
        return BundleRegistry.empty().use { registry ->
            dropMm1(dir)
            BundleDirectoryWatcher(registry, dir).scanOnce()
            assertTrue(registry.listBundles().any { it.bundleId == bundleId }, "fixture bundle should load")
            block(RegistryModelProvider(registry))
        }
    }

    /**
     * A configuration shaped the way the desktop apps save one. `bundleRefs` matters: a
     * `byBundleAndModelId` scenario must declare its bundle at document level or validation fails
     * with SCENARIO_BUNDLE_REF_MISSING, independently of any provider. The declared `paths` are an
     * authoring-machine artifact and are checked only for blankness — never for existence — which
     * is what lets a file authored on one machine validate on a server that resolves the same
     * bundle from its own registry.
     */
    private fun configWith(reference: ModelReference, declareBundle: Boolean = true) =
        RunConfiguration(
            scenarios = listOf(ScenarioSpec(name = "portable", modelReference = reference)),
            bundleRefs = if (declareBundle) {
                listOf(BundleRef(bundleId = bundleId, paths = listOf("/some/authoring/machine/mm1.jar")))
            } else {
                emptyList()
            },
        )

    @Test
    @DisplayName("the registry provider resolves the unambiguous (bundleId, modelId) pair")
    fun registryResolvesTheBundlePair() {
        withRegistryProvider { provider ->
            assertTrue(provider.isModelProvided(bundleId, modelId), "registry should provide the pair")
            val model = provider.provideModel(bundleId, modelId)
            assertTrue(model.name.isNotBlank(), "a model should be built from the pair")
        }
    }

    @Test
    @DisplayName("an app-saved byBundleAndModelId config validates on the server")
    fun appSavedConfigValidatesAgainstTheRegistry() {
        withRegistryProvider { provider ->
            val result = RunConfigurationValidator.validateForRun(
                configWith(ModelReference.ByBundleAndModelId(bundleId, modelId)),
                provider,
            )
            assertTrue(
                result.errors.none { it.code == "BUNDLE_MODEL_PROVIDER_REQUIRED" },
                "the registry must satisfy the bundle-pair contract; errors were: ${result.errors}",
            )
            assertTrue(result.isValid, "app-saved config should validate on the server; errors: ${result.errors}")
        }
    }

    @Test
    @DisplayName("a server-native byProviderId config still validates — no regression")
    fun byProviderIdStillValidates() {
        withRegistryProvider { provider ->
            val result = RunConfigurationValidator.validateForRun(
                configWith(ModelReference.ByProviderId(modelId)),
                provider,
            )
            assertTrue(result.isValid, "byProviderId should still validate; errors: ${result.errors}")
        }
    }

    @Test
    @DisplayName("byBundleAndModelId still requires the bundle to be declared in bundleRefs")
    fun bundleRefsAreStillRequired() {
        withRegistryProvider { provider ->
            val result = RunConfigurationValidator.validateForRun(
                configWith(ModelReference.ByBundleAndModelId(bundleId, modelId), declareBundle = false),
                provider,
            )
            assertTrue(!result.isValid, "an undeclared bundle must not validate")
            assertTrue(
                result.errors.any { it.code == "SCENARIO_BUNDLE_REF_MISSING" },
                "the document rule is independent of the provider; errors: ${result.errors}",
            )
        }
    }

    @Test
    @DisplayName("an unknown bundle still fails, and not with the provider-type error")
    fun unknownBundleStillFails() {
        withRegistryProvider { provider ->
            val result = RunConfigurationValidator.validateForRun(
                configWith(ModelReference.ByBundleAndModelId("no.such.bundle", modelId)),
                provider,
            )
            assertTrue(!result.isValid, "an unknown bundle must not validate")
            assertEquals(
                0,
                result.errors.count { it.code == "BUNDLE_MODEL_PROVIDER_REQUIRED" },
                "the failure should be about the missing bundle, not the provider's type",
            )
        }
    }
}
