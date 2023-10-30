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

import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.File
import java.io.PrintWriter
import java.nio.file.Path
import java.util.*

object KSL {

    fun randomUUIDString() = UUID.randomUUID().toString()

    /**
     * A global logger for logging
     */
    val logger = KotlinLogging.logger {}

    /**
     * Used to assign unique enum constants
     */
    private var myEnumCounter = 0

    internal val myOutputDir = OutputDirectory("kslOutput", "kslOutput.txt")

    /**
     *  Use with println(), but it goes to a file called kslOutput.txt
     */
    val out = myOutputDir.out

    /**
     *
     * the path to the base directory
     */
    val outDir = myOutputDir.outDir

    /**
     *
     * the path to the default excel directory
     */
    val excelDir = myOutputDir.excelDir

    /**
     *
     * the path to the default database directory
     */
    val dbDir = myOutputDir.dbDir

    /**
     *  the path to the default comma separated value file directory
     */
    val csvDir = myOutputDir.csvDir

    /**
     *  the path to the default file directory for plotting output
     */
    val plotDir = myOutputDir.plotDir

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


}