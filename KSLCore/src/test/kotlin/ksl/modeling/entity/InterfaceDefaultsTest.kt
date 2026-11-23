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

package ksl.modeling.entity

import ksl.utilities.statistic.StateAccessorIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  The members R1.9 added to `ResourceCIfc` (`isFailed`, `failedState`) and `AllocationIfc` (`timesInterrupted`,
 *  `timeInterrupted`, `hasMissingUnits`) have defaults, so an implementation written before them still compiles and
 *  reports a resource that never fails and an allocation never interrupted.
 */
class InterfaceDefaultsTest {

    private val added = mapOf(
        ResourceCIfc::class.java to setOf("getFailedState", "isFailed"),
        AllocationIfc::class.java to setOf("getTimesInterrupted", "getTimeInterrupted", "getHasMissingUnits")
    )

    @Test
    @DisplayName("the added members are not abstract")
    fun addedMembersHaveDefaults() {
        for ((api, names) in added) {
            for (name in names) {
                val method = api.methods.first { it.name == name }
                assertFalse(Modifier.isAbstract(method.modifiers), "${api.simpleName}.$name is abstract")
            }
        }
    }

    @Test
    @DisplayName("a Java class implementing only the other members compiles, loads, and reports the defaults")
    fun javaImplementationWithoutTheAddedMembers() {
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "a JDK compiler is needed")
        val dir = createTempDirectory("ksl-interface-defaults-").toFile()
        val sources = added.keys.map { api ->
            val body = api.methods.filter { Modifier.isAbstract(it.modifiers) }
                .distinctBy { it.name + it.parameterTypes.joinToString { p -> p.name } }
                .joinToString("\n") { m ->
                    val r = m.returnType
                    val value = when {
                        r == Void.TYPE -> ""
                        r == java.lang.Boolean.TYPE -> "return false;"
                        r == java.lang.Character.TYPE -> "return (char) 0;"
                        r.isPrimitive -> "return 0;"
                        else -> "return null;"
                    }
                    "public ${r.canonicalName} ${m.name}(" +
                        m.parameterTypes.mapIndexed { i, p -> "${p.canonicalName} a$i" }.joinToString(", ") + ") { $value }"
                }
            File(dir, "Legacy${api.simpleName}.java").apply {
                writeText("public class Legacy${api.simpleName} implements ${api.canonicalName} {\n$body\n}")
            }
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, null).use { files ->
            val classPath = listOf(
                System.getProperty("java.class.path"),
                File(ResourceCIfc::class.java.protectionDomain.codeSource.location.toURI()).absolutePath,
                File(Unit::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
            ).joinToString(File.pathSeparator)
            val ok = compiler.getTask(
                null, files, diagnostics, listOf("-classpath", classPath, "-d", dir.absolutePath, "-Xlint:none"),
                null, files.getJavaFileObjects(*sources.toTypedArray())
            ).call()
            assertTrue(ok, diagnostics.diagnostics.joinToString("\n") { it.getMessage(null) })
        }
        URLClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader).use { loader ->
            val resource = loader.loadClass("LegacyResourceCIfc").getDeclaredConstructor().newInstance() as ResourceCIfc
            assertFalse(resource.isFailed)
            val failed: StateAccessorIfc = resource.failedState
            assertFalse(failed.isEntered)
            assertEquals(0.0, failed.numberOfTimesEntered)
            assertEquals(0.0, failed.totalTimeInState)
            val allocation = loader.loadClass("LegacyAllocationIfc").getDeclaredConstructor().newInstance() as AllocationIfc
            assertEquals(0, allocation.timesInterrupted)
            assertEquals(0.0, allocation.timeInterrupted)
            assertFalse(allocation.hasMissingUnits)
        }
    }
}
