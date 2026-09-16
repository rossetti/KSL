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

package ksl.examples.general.vehiclebundle

import ksl.app.bundle.BundleLoader
import ksl.app.bundle.LoadedBundle
import ksl.simulation.CatalogValidation
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Verification for the vehicle-examples bundle, the in-suite equivalent of a `kslpkg validate`
 *  pass: every nominated catalog input and output resolves against the built model's actual
 *  control / RV-parameter / response surface, and every model builds and runs.
 *
 *  The drift this is here to catch is silent otherwise: a nomination is a **string**, so renaming a
 *  response or a random variable in an example breaks its catalog without breaking the compile.
 *
 *  It says nothing about *packaging* -- it assembles from the classpath, so it would pass whether or
 *  not the shipped jar contained the model classes. [VehicleBundleClosureTest] is what checks that.
 *
 *  The expected-id list is hand-maintained on purpose. Adding a model to the bundle should be a
 *  decision, and a test that derived the list from the builders themselves would pass however many
 *  turned up.
 */
class VehicleExamplesBundleTest {

    /** Guide-path examples: vehicles that contend for the space they travel through. */
    private val guidePathModelIds = listOf(
        VehicleBundleFixture.SIMPLE_AGV_SHOP,
        VehicleBundleFixture.GUIDE_PATH_DISTURBANCES,
        VehicleBundleFixture.PEDESTRIAN_CROSSING,
    )

    /** Active-fleet examples: a dispatcher decides, over a guide path or over a plane. */
    private val fleetModelIds = listOf(
        VehicleBundleFixture.MULTI_FLOOR_HOSPITAL,
        VehicleBundleFixture.TWO_LANE_WAREHOUSE,
        VehicleBundleFixture.FREE_PATH_FLEET_YARD,
        VehicleBundleFixture.DISPATCHING_RULES_RING_SHOP,
    )

    /** The same shop modelled both ways, and the chapter 8 shop's guided-path variant. */
    private val comparisonModelIds = listOf(
        VehicleBundleFixture.PASSIVE_TRANSPORTER_SHOP,
        VehicleBundleFixture.ACTIVE_FLEET_SHOP,
        VehicleBundleFixture.TEST_AND_REPAIR_GUIDED_TRANSPORTERS,
    )

    private val allModelIds = guidePathModelIds + fleetModelIds + comparisonModelIds

    private fun withVehicleBundle(block: (LoadedBundle) -> Unit) {
        val dir = Files.createTempDirectory("vehicle-bundle-test")
        try {
            val jar = VehicleBundleFixture.assemble(dir)
            BundleLoader.loadJar(jar).single().use { block(it) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("the bundle is discovered with every expected model")
    fun `bundle is discovered with all expected models`() {
        withVehicleBundle { match ->
            val ids = match.bundle.models.map { it.modelId }
            assertTrue(
                ids.containsAll(allModelIds),
                "Bundle must expose every expected model; got $ids"
            )
        }
    }

    @Test
    @DisplayName("every catalog input and output resolves against the model surface")
    fun `every catalog input and output resolves against the model surface`() {
        withVehicleBundle { match ->
            for (id in allModelIds) {
                val descriptor = match.descriptorFor(id)
                val catalog = descriptor.catalog
                assertNotNull(catalog, "Model '$id' must carry a catalog")
                assertTrue(catalog.nominatedInputs.isNotEmpty(), "Model '$id' must nominate inputs")
                assertTrue(catalog.nominatedOutputs.isNotEmpty(), "Model '$id' must nominate outputs")
                // The framework's own rule, rather than a narrower one re-derived here. Two of
                // these models nominate a STRING control, which is a perfectly good catalog input
                // and is deliberately absent from `descriptor.inputNames` -- that set is the
                // numerically sweepable surface, which is a different question.
                val problems = CatalogValidation.validate(catalog, descriptor)
                val errors = problems.filter { it.severity == CatalogValidation.Severity.ERROR }
                assertTrue(
                    errors.isEmpty(),
                    "Model '$id' has catalog references that do not resolve:\n" +
                        errors.joinToString("\n") { "  ${it.subject}: ${it.message}" }
                )
            }
        }
    }

    @Test
    @DisplayName("every model builds and runs and produces something")
    fun `every model builds and runs`() {
        withVehicleBundle { match ->
            for (id in allModelIds) {
                val bundled = match.bundle.models.first { it.modelId == id }
                val model = bundled.builder().build(null, null)
                // Shrink the run so the smoke stays fast. The test-and-repair shop's natural horizon
                // is a simulated year.
                model.numberOfReplications = 2
                if (model.lengthOfReplication.isFinite() && model.lengthOfReplication > 2000.0) {
                    model.lengthOfReplication = 2000.0
                    model.lengthOfReplicationWarmUp = 0.0
                }
                model.simulate()
                assertTrue(
                    model.responses.isNotEmpty() || model.counters.isNotEmpty(),
                    "Model '$id' produced no responses or counters"
                )
            }
        }
    }

    /**
     *  The two disciplines-by-name models are the reason the bundle needs a string control at all,
     *  so the names they declare are checked against the names they will actually accept. A
     *  `@KSLStringControl` whose `allowedValues` had drifted from the map behind the setter would
     *  offer an app a value the model then refuses.
     */
    @Test
    @DisplayName("every declared discipline name is one the model accepts")
    fun `declared discipline names are accepted`() {
        withVehicleBundle { match ->
            for ((id, key) in listOf(
                VehicleBundleFixture.DISPATCHING_RULES_RING_SHOP to "Shop.ruleName",
                VehicleBundleFixture.PEDESTRIAN_CROSSING to "Town.arbiterName",
            )) {
                val bundled = match.bundle.models.first { it.modelId == id }
                val model = bundled.builder().build(null, null)
                val control = model.controls().stringControl(key)
                assertNotNull(control, "Model '$id' must expose the string control '$key'")
                assertTrue(
                    control.allowedValues.isNotEmpty(),
                    "'$key' must declare the values it accepts, or it is not a choice"
                )
                for (value in control.allowedValues) {
                    control.value = value
                    assertTrue(
                        control.value == value,
                        "'$key' declares '$value' but did not take it"
                    )
                }
            }
        }
    }

    /**
     *  An arrival rate nominated through a generator's own random variable is nominated by
     *  **name**, and a name that does not resolve is dropped with a recorded problem rather than
     *  raised -- so the catalog would simply come back one input short and every other check here
     *  would still pass. This is the check that would not: the two models whose load is driven by a
     *  named [ksl.modeling.elements.EventGenerator] must carry that generator's mean time between
     *  events as an input. It fails if a generator is renamed, or if its name is dropped and the
     *  element falls back to an `ID_n` key.
     */
    @Test
    @DisplayName("each generator-driven model nominates its arrival rate by the generator's name")
    fun `generator driven models nominate their arrival rate`() {
        withVehicleBundle { match ->
            for ((id, keys) in listOf(
                VehicleBundleFixture.SIMPLE_AGV_SHOP to listOf("PartArrivals:TimeBtwEventsRV.mean"),
                VehicleBundleFixture.GUIDE_PATH_DISTURBANCES to listOf(
                    "PartArrivals:TimeBtwEventsRV.mean",
                    "SpillArrivals:TimeBtwEventsRV.mean"
                ),
            )) {
                val catalog = assertNotNull(
                    match.descriptorFor(id).catalog, "Model '$id' must carry a catalog"
                )
                val nominated = catalog.nominatedInputs.map { it.key }
                for (key in keys) {
                    assertTrue(
                        key in nominated,
                        "Model '$id' must nominate '$key'; it nominates $nominated"
                    )
                }
            }
        }
    }
}
