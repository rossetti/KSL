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

import ksl.app.bundle.BundleAuthoringSession
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 *  Test helper: assembles the vehicle-example models into a manifest bundle JAR from their named
 *  builders, via `BundleAuthoringSession` -- the same path `kslpkg assemble` uses, and therefore the
 *  same path the Gradle `vehicleExamplesBundleJar` task uses.
 *
 *  The builders JAR is written inline rather than through `KSLTestModels` for the reason
 *  [ksl.examples.general.bookbundle.BookBundleFixture] gives: a `KSLTestModels` test dependency
 *  would leak the appsupport ServiceLoader bundles onto KSLExamples' test classpath.
 */
internal object VehicleBundleFixture {

    /** Stable, globally unique id of the vehicle-examples bundle. */
    const val BUNDLE_ID: String = "edu.uark.ksl.vehicle-examples"

    // ── Model ids (builder class name minus the `ModelBuilder` suffix) ──
    const val SIMPLE_AGV_SHOP: String = "SimpleAgvShop"
    const val GUIDE_PATH_DISTURBANCES: String = "GuidePathDisturbances"
    const val MULTI_FLOOR_HOSPITAL: String = "MultiFloorHospital"
    const val TWO_LANE_WAREHOUSE: String = "TwoLaneWarehouse"
    const val FREE_PATH_FLEET_YARD: String = "FreePathFleetYard"
    const val PASSIVE_TRANSPORTER_SHOP: String = "PassiveTransporterShop"
    const val ACTIVE_FLEET_SHOP: String = "ActiveFleetShop"
    const val DISPATCHING_RULES_RING_SHOP: String = "DispatchingRulesRingShop"
    const val PEDESTRIAN_CROSSING: String = "PedestrianCrossing"
    const val TEST_AND_REPAIR_GUIDED_TRANSPORTERS: String = "TestAndRepairShopWithGuidedTransporters"

    /** The named vehicle-example builders, one per bundled model. */
    val builders: List<Class<out ModelBuilderIfc>> = listOf(
        SimpleAgvShopModelBuilder::class.java,
        GuidePathDisturbancesModelBuilder::class.java,
        MultiFloorHospitalModelBuilder::class.java,
        TwoLaneWarehouseModelBuilder::class.java,
        FreePathFleetYardModelBuilder::class.java,
        PassiveTransporterShopModelBuilder::class.java,
        ActiveFleetShopModelBuilder::class.java,
        DispatchingRulesRingShopModelBuilder::class.java,
        PedestrianCrossingModelBuilder::class.java,
        TestAndRepairShopWithGuidedTransportersModelBuilder::class.java,
    )

    /** Assembles the builders into a bundle JAR at `<dir>/vehicle-examples.jar`. */
    fun assemble(dir: Path, bundleId: String = BUNDLE_ID): Path {
        val buildersJar = writeBuildersJar(dir, "vehicle-builders", builders)
        val session = BundleAuthoringSession.open(buildersJar)
        session.bundleId = bundleId
        val output = dir.resolve("vehicle-examples.jar")
        session.assemble(output, force = true)
        return output
    }

    /** Writes a classes-only builders JAR holding each builder class file + its Kotlin synthetic lambdas. */
    private fun writeBuildersJar(dir: Path, name: String, classes: List<Class<*>>): Path {
        val target = dir.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            val seen = mutableSetOf<String>()
            for (cls in classes) addClassWithInnerClasses(jar, cls, seen)
        }
        return target
    }

    private fun addClassWithInnerClasses(jar: JarOutputStream, cls: Class<*>, seen: MutableSet<String>) {
        addClass(jar, cls, seen)
        val pkgPath = cls.`package`.name.replace('.', '/')
        val pkgDir = cls.classLoader.getResource(pkgPath)?.let {
            try { Paths.get(it.toURI()) } catch (_: Exception) { null }
        } ?: return
        if (!Files.isDirectory(pkgDir)) return
        Files.list(pkgDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("${cls.simpleName}\$") }
                .filter { it.fileName.toString().endsWith(".class") }
                .forEach { sibling ->
                    val entryName = "$pkgPath/${sibling.fileName}"
                    if (seen.add(entryName)) {
                        jar.putNextEntry(JarEntry(entryName).apply { time = 0L })
                        jar.write(Files.readAllBytes(sibling))
                        jar.closeEntry()
                    }
                }
        }
    }

    private fun addClass(jar: JarOutputStream, cls: Class<*>, seen: MutableSet<String>) {
        val entryName = cls.name.replace('.', '/') + ".class"
        if (!seen.add(entryName)) return
        val bytes = cls.classLoader.getResourceAsStream(entryName)?.use { it.readBytes() }
            ?: error("Cannot locate class file for ${cls.name} on the classpath")
        jar.putNextEntry(JarEntry(entryName).apply { time = 0L })
        jar.write(bytes)
        jar.closeEntry()
    }
}
