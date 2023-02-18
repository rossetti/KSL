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

import mu.KotlinLogging
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

//private val logger = KotlinLogging.logger {}

/**
 * This class provides basic context for creating and writing output files.
 * Files and directories created by instances of this class will be relative to
 * the Path supplied at creation.
 *
 * @param outputDirectoryPath the base output directory to use for writing text files relative to this OutputDirectory instance
 * @param outFileName the name of the created text file related to property out
 */
class OutputDirectory(outputDirectoryPath: Path = KSLFileUtil.programLaunchDirectory, outFileName: String = "out.txt") {

    /**
     *
     * @return the path to the base directory for this OutputDirectory
     */
    /**
     * The path to the default output directory
     */
    var outDir: Path = createDirectoryPath(outputDirectoryPath)

    /**
     * Can be used like System.out, but instead writes to a file
     * found in the base output directory
     */
    val out: LogPrintWriter = KSLFileUtil.createLogPrintWriter(outDir.resolve(outFileName))

    /** Creates a OutputDirectory with the current program launch directory with the base directory
     *
     * @param outDirName the name of the directory within the current launch directory, default "OutputDir"
     * @param outFileName the name of the created text file related to property out, default "out.txt"
     */
    constructor(
        outDirName: String = "OutputDir",
        outFileName: String = "out.txt"
    ) : this(KSLFileUtil.programLaunchDirectory.resolve(outDirName), outFileName)

    /** Creates a path to a directory by creating all nonexistent parent directories first,
     *  like Files.createDirectories().
     *
     *  If there is a problem with the creation, then KSLFileUtil.programLaunchDirectory is used
     *
     *  @param pathToDir the path to the directory
     */
    private fun createDirectoryPath(pathToDir: Path) : Path {
        return try {
            Files.createDirectories(pathToDir)
        } catch (e: IOException) {
            KSLFileUtil.logger.info("There was a problem creating the directories for {} used program launch directory", pathToDir)
            KSLFileUtil.programLaunchDirectory
        }
    }

    /**
     * The path to the default Excel directory, relative to this output directory
     */
    val excelDir: Path = createExcelDirectory()

    private fun createExcelDirectory(): Path {
        return try {
            Files.createDirectories(outDir.resolve("excelDir"))
        } catch (e: IOException) {
            KSLFileUtil.logger.info("There was a problem creating the directories for {} used program launch directory",
                outDir.resolve("excel"))
            KSLFileUtil.programLaunchDirectory
        }
    }

    val dbDir: Path = createDatabaseDirectory()

    private fun createDatabaseDirectory(): Path {
        return try {
            Files.createDirectories(outDir.resolve("dbDir"))
        } catch (e: IOException) {
            KSLFileUtil.logger.info("There was a problem creating the directories for {} used program launch directory",
                outDir.resolve("db"))
            KSLFileUtil.programLaunchDirectory
        }
    }

    val csvDir: Path = createCSVDirectory()
    
    private fun createCSVDirectory(): Path {
        return try {
            Files.createDirectories(outDir.resolve("csvDir"))
        } catch (e: IOException) {
            KSLFileUtil.logger.info("There was a problem creating the directories for {} used program launch directory",
                outDir.resolve("excel"))
            KSLFileUtil.programLaunchDirectory
        }
    }


    /** Makes a new PrintWriter within the base directory with the given file name
     *
     * @param fileName the name of the file for the PrintWriter
     * @return the PrintWriter, or System.out if there was some problem with its creation
     */
    fun createPrintWriter(fileName: String): PrintWriter {
        val newFilePath = outDir.resolve(fileName)
        return KSLFileUtil.createPrintWriter(newFilePath)
    }

    /** Makes a new PrintWriter within the base directory with the given file name
     *
     * @param fileName the name of the file for the PrintWriter
     * @return the File in the base directory
     */
    fun createFile(fileName: String): File {
        val newFilePath = outDir.resolve(fileName)
        return newFilePath.toFile()
    }

    /** Makes a Path to the named subdirectory within the base directory
     *
     * @param dirName the name of the subdirectory to create. It must not be null
     * @return a path to the created subdirectory, or the base directory if something went wrong in the creation.
     * Any problems are logged.
     */
    fun createSubDirectory(dirName: String): Path {
        return KSLFileUtil.createSubDirectory(outDir, dirName)
    }

}