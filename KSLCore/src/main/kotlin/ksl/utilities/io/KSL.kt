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

package ksl.utilities.io

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.File
import java.io.PrintWriter
import java.nio.file.Path
import java.util.*

object KSL {

    fun randomUUIDString() : String = UUID.randomUUID().toString()

    /**
     * A global logger for logging
     */
    val logger: KLogger = KotlinLogging.logger {}

    /**
     *  Controls whether `consoleAdvisory` and `consoleDiagnostic` write to the
     *  console in addition to logging. Defaults to true so that users who never
     *  look at log files (especially students running models from an IDE) see
     *  mistake-preventing advisories directly in their console. Headless or
     *  test environments can suppress console output by setting the system
     *  property `ksl.consoleAdvisories` to false or by assigning this property.
     */
    var consoleAdvisoriesOption: Boolean =
        System.getProperty("ksl.consoleAdvisories")?.toBooleanStrictOrNull() ?: true

    /**
     *  Emits a usability advisory. The message is always logged at WARN via the
     *  supplied logger and, unless `consoleAdvisoriesOption` is false, is also
     *  printed to standard output with a "KSL ADVISORY:" prefix.
     *
     *  This is the single sanctioned channel for console output in core library
     *  execution paths (outside explicit print/report APIs). It is intended for
     *  rare, mistake-preventing messages that must reach users who never look at
     *  log files, e.g. configurations that would cause a simulation to run forever.
     *
     * @param logger the logger that records the advisory at WARN
     * @param message the advisory message
     */
    fun consoleAdvisory(logger: KLogger, message: () -> String) {
        logger.warn(message)
        if (consoleAdvisoriesOption) {
            println("KSL ADVISORY: ${message()}")
            System.out.flush()
        }
    }

    /**
     *  Emits a failure diagnostic. The message is always logged at ERROR via the
     *  supplied logger and, unless `consoleAdvisoriesOption` is false, is also
     *  printed to standard error.
     *
     *  Use this only when the diagnostic context is not carried by a thrown
     *  exception (e.g. the simulation state surrounding a failed event) and would
     *  otherwise be invisible to users who never look at log files.
     *
     * @param logger the logger that records the diagnostic at ERROR
     * @param message the diagnostic message
     */
    fun consoleDiagnostic(logger: KLogger, message: () -> String) {
        logger.error(message)
        if (consoleAdvisoriesOption) {
            System.err.println(message())
            System.err.flush()
        }
    }

    /**
     * Used to assign unique enum constants
     */
    private var myEnumCounter = 0

    // Base output directory: "kslOutput" under the program launch directory by
    // default — unchanged for IDE / student runs, so `kslOutput/` still appears at
    // the project root. The `ksl.outputDir` system property overrides it; this repo's
    // Gradle test convention sets it so test output lands under build/ instead of
    // littering each module's source tree. An absolute value is used as-is (Path.resolve
    // returns an absolute argument unchanged); a relative one resolves under the launch dir.
    internal val myOutputDir = OutputDirectory(
        System.getProperty("ksl.outputDir") ?: "kslOutput",
        "kslOutput.txt"
    )

    /**
     *  Use with println(), but it goes to a file called kslOutput.txt
     */
    val out: LogPrintWriter = myOutputDir.out

    /**
     *
     * the path to the base directory
     */
    val outDir: Path = myOutputDir.outDir

    /**
     *
     * the path to the default Excel directory
     */
    val excelDir: Path = myOutputDir.excelDir

    /**
     *
     * the path to the default database directory
     */
    val dbDir: Path = myOutputDir.dbDir

    /**
     *  the path to the default comma-separated value file directory
     */
    val csvDir: Path = myOutputDir.csvDir

    /**
     *  the path to the default file directory for plotting output
     */
    val plotDir: Path = myOutputDir.plotDir

    /**
     * Should be used by classes to get the next constant
     * so that unique constants can be used
     *
     * @return the constant
     */
    val nextEnumConstant: Int
        get() = ++myEnumCounter

    /** Makes a new PrintWriter within the base directory with the given file name
     *
     * @param fileName the name of the file for the PrintWriter
     * @return the PrintWriter, or System.out if there was some problem with its creation
     */
    fun createPrintWriter(fileName: String): PrintWriter {
        return myOutputDir.createPrintWriter(fileName)
    }

    /** Makes a new file within the base directory with the given file name
     *
     * @param fileName the name of the file for the PrintWriter
     * @return the File in the base directory
     */
    fun createFile(fileName: String): File {
        return myOutputDir.createFile(fileName)
    }

    override fun toString(): String {
        return myOutputDir.toString()
    }

    /** Makes a Path to the named subdirectory within the base directory
     *
     * @param dirName the name of the subdirectory to create. It must not be null
     * @return a path to the created subdirectory, or the base directory if something went wrong in the creation.
     * Any problems are logged.
     */
    fun createSubDirectory(dirName: String): Path {
        return myOutputDir.createSubDirectory(dirName)
    }

    /** Resolves a path to the named subdirectory under the base output directory
     *  WITHOUT creating it. This is the path-only sibling of `createSubDirectory`.
     *
     *  Model's constructor-default output path uses this so that a model that never
     *  writes output materializes no directory. The directory is created later, on
     *  first actual output (when the model's output directory is first used).
     *
     *  Output abstractions that DO write at construction time (e.g. the scenario /
     *  experiment runners, which open a results `KSLDatabase` in their output
     *  directory) must keep using `createSubDirectory` so the directory exists.
     *
     * @param dirName the name of the subdirectory to resolve. It must not be null
     * @return a path to the named subdirectory under the output root, not created
     */
    fun outputSubPath(dirName: String): Path = outDir.resolve(dirName)


}