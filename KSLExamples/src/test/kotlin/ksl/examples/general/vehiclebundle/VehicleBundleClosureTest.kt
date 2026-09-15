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

import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.assertTrue

/**
 *  That the shipped jar actually **contains** the models it claims to.
 *
 *  This bundle does not copy its models: the builders import the examples where they live, and the
 *  `vehicleBuildersJar` task reaches them with a list of include patterns. That list is the weak
 *  point. It is matched against paths, not against the call graph, so a builder whose model sits in
 *  a package nobody added to the list is packaged as an entry that cannot be built — and nothing at
 *  compile time, and no ordinary test, says so: the test JVM has the whole project on its classpath
 *  and would find the very class the jar is missing.
 *
 *  So this loads each builder **out of the jar**, through a class loader that refuses to fall back
 *  to the project's own classes for anything under `ksl.examples`. That is the situation a user is
 *  in: KSL on the classpath, and a bundle supplying the models. A class left out of the jar fails
 *  here exactly as it would there.
 *
 *  Borrowed wholesale from [ksl.examples.general.animationbundle.AnimationBundleClosureTest], which
 *  exists because two animation builders shipped in exactly that state.
 */
class VehicleBundleClosureTest {

    /** Every builder the bundle ships, by class name — mirroring `VehicleExampleBuilders`. */
    private val bundled = listOf(
        "SimpleAgvShopModelBuilder",
        "GuidePathDisturbancesModelBuilder",
        "MultiFloorHospitalModelBuilder",
        "TwoLaneWarehouseModelBuilder",
        "FreePathFleetYardModelBuilder",
        "PassiveTransporterShopModelBuilder",
        "ActiveFleetShopModelBuilder",
        "DispatchingRulesRingShopModelBuilder",
        "PedestrianCrossingModelBuilder",
        "TestAndRepairShopWithGuidedTransportersModelBuilder",
    )

    @Test
    @DisplayName("every bundled model builds from the jar alone")
    fun everyBundledModelBuildsFromTheJar() {
        val jar = File(System.getProperty("vehicleBundleJar") ?: "")
        assumeTrue(jar.isFile, "no builders jar; run :KSLExamples:vehicleBuildersJar")

        val failures = LinkedHashMap<String, String>()
        BundleOnlyClassLoader(jar.toURI().toURL(), javaClass.classLoader).use { loader ->
            for (name in bundled) {
                val qualified = "ksl.examples.general.vehiclebundle.$name"
                runCatching {
                    val builder = loader.loadClass(qualified).getDeclaredConstructor().newInstance()
                    (builder as ModelBuilderIfc).build(null, null)
                }.onFailure { failures[name] = "${it::class.simpleName}: ${it.message}" }
            }
        }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("These bundled examples cannot be built from the vehicle-builders jar, so they")
                appendLine("would fail for a user. Usually the model class is not matched by any include")
                appendLine("pattern of the vehicleBuildersJar task in KSLExamples/build.gradle.kts:")
                failures.forEach { (name, why) -> appendLine("  $name — $why") }
            }
        )
    }

    /**
     *  Loads anything under `ksl.examples` from the bundle jar and nothing else from anywhere else.
     *
     *  Delegating to the parent first — the normal order — would find the project's own compiled
     *  classes and the test would pass whether or not the jar contained them.
     */
    private class BundleOnlyClassLoader(jar: URL, parent: ClassLoader) : URLClassLoader(arrayOf(jar), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("ksl.examples.")) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                return findClass(name).also { if (resolve) resolveClass(it) }
            }
        }
    }
}
