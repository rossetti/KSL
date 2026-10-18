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

package ksl.utilities.io

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Opening a file in the browser on a machine with no display writes the file and returns, rather than
 *  stopping the program with a `HeadlessException`.
 *
 *  The test JVM is deliberately not headless (see the root build script), so the check runs in a child JVM
 *  started with `-Djava.awt.headless=true` on the same classpath. That also keeps the test from opening a
 *  real browser on a developer's machine.
 */
class HeadlessBrowserTest {

    @Test
    fun openInBrowserReturnsNormallyWithNoDisplay() {
        val dir = Files.createTempDirectory("ksl_headless_browser")
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(
                java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                HeadlessProbe::class.java.name, dir.toString()
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe finished")
            assertEquals(0, process.exitValue(), "the probe exited normally:\n$output")
            assertTrue(output.contains("returned"), "execution continued after the call:\n$output")
            val written = dir.toFile().listFiles { f -> f.name.endsWith(".html") }.orEmpty()
            assertEquals(1, written.size, "the HTML file was written")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

/** Run in a headless child JVM by [HeadlessBrowserTest]. */
object HeadlessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        KSLFileUtil.openInBrowser("probe", "<html><body>probe</body></html>", java.nio.file.Path.of(args[0]))
        println("returned")
    }
}
