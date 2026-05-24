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

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path

/**
 * This class provides basic context for creating and writing output files.
 * Files and directories created by instances of this class will be relative to
 * the Path supplied at creation.
 *
 * ### Side-effects at construction
 *
 * - [outDir] (the base directory) is created eagerly.
 * - [out] (the log writer) is created eagerly **only when** [autoCreateOutFile]
 *   is `true` (the default).  When `false`, [out] is a discard-all writer
 *   wrapping `Writer.nullWriter()` and no file is created on disk.
 * - The four subdirectory properties ([excelDir], [dbDir], [csvDir],
 *   [plotDir]) are **lazy**.  Each subdirectory is created on first access
 *   to its property and not before.  Code that never reads a subdirectory
 *   property never causes its directory to appear on disk.
 *
 * The laziness lets callers that fan out an `OutputDirectory` per
 * simulation run (e.g. `ParallelDesignedExperiment` per design point,
 * `ConcurrentScenarioRunner` per scenario) avoid materialising empty
 * format-specific subdirectories under every run's folder when the model
 * never writes Excel / DB / CSV / plot artefacts.  The
 * [autoCreateOutFile] flag lets those same callers suppress the per-run
 * log file when they're operating in a flat (shared-directory) mode where
 * a per-run log file would be noise rather than signal.
 *
 * @param outputDirectoryPath the base output directory to use for writing
 *                            text files relative to this OutputDirectory
 *                            instance
 * @param outFileName         the name of the file backing the [out] property
 *                            when [autoCreateOutFile] is `true`; ignored
 *                            otherwise
 * @param autoCreateOutFile   when `true` (default), create [outFileName]
 *                            under [outDir] at construction time and back
 *                            [out] with it.  When `false`, no file is
 *                            created and [out] is a no-op writer that
 *                            discards everything written to it.
 */
class OutputDirectory(
    outputDirectoryPath: Path = KSLFileUtil.programLaunchDirectory,
    private val outFileName: String = "out.txt",
    private val autoCreateOutFile: Boolean = true
) {

    /**
     * The path to the default output directory.  Always created eagerly
     * regardless of [autoCreateOutFile] — the directory itself is the
     * routing target the caller supplied; only the file contents are
     * optional.
     */
    var outDir: Path = createDirectoryPath(outputDirectoryPath)

    /**
     * Can be used like System.out, but writes to a file in [outDir] when
     * [autoCreateOutFile] is `true`.  When `false`, this writer discards
     * everything written to it (wraps `Writer.nullWriter()`) and no file
     * is created on disk.
     */
    val out: LogPrintWriter = if (autoCreateOutFile) {
        KSLFileUtil.createLogPrintWriter(outDir.resolve(outFileName))
    } else {
        LogPrintWriter(Writer.nullWriter())
    }

    /** Creates a OutputDirectory with the current program launch directory with the base directory
     *
     * @param outDirName the name of the directory within the current launch directory, default "OutputDir"
     * @param outFileName the name of the created text file related to property out, default "out.txt"
     * @param autoCreateOutFile see the primary constructor
     */
    constructor(
        outDirName: String = "OutputDir",
        outFileName: String = "out.txt",
        autoCreateOutFile: Boolean = true
    ) : this(
        KSLFileUtil.programLaunchDirectory.resolve(outDirName),
        outFileName,
        autoCreateOutFile
    )

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
            KSLFileUtil.logger.info { "There was a problem creating the directories for $pathToDir used program launch directory" }
            KSLFileUtil.programLaunchDirectory
        }
    }

    /**
     * The path to the default Excel directory, relative to this output directory.
     * Lazily created on first access — `OutputDirectory` instances that never
     * read this property never cause `excelDir/` to appear on disk.
     */
    val excelDir: Path by lazy { resolveAndCreate(EXCEL_DIR_NAME) }

    /**
     * The path to the default database directory, relative to this output
     * directory.  Lazily created on first access (see [excelDir]).
     */
    val dbDir: Path by lazy { resolveAndCreate(DB_DIR_NAME) }

    /**
     * The path to the default CSV directory, relative to this output
     * directory.  Lazily created on first access (see [excelDir]).
     */
    val csvDir: Path by lazy { resolveAndCreate(CSV_DIR_NAME) }

    /**
     * The path to the default plot directory, relative to this output
     * directory.  Lazily created on first access (see [excelDir]).
     */
    val plotDir: Path by lazy { resolveAndCreate(PLOT_DIR_NAME) }

    /** Resolves [name] under [outDir] and ensures the directory exists.
     *  Used by the four lazy subdirectory properties — every previous
     *  per-subdir factory function reduced to the same body, so they
     *  collapse into one helper. */
    private fun resolveAndCreate(name: String): Path {
        return try {
            Files.createDirectories(outDir.resolve(name))
        } catch (e: IOException) {
            KSLFileUtil.logger.info {
                "There was a problem creating the directories for ${outDir.resolve(name)} used program launch directory"
            }
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

    /** Resolves a path to the named subdirectory without creating it.
     *  Used by [toString] so that printing an `OutputDirectory` doesn't
     *  trigger materialisation of the lazy subdirectories. */
    override fun toString(): String {
        return buildString {
            appendLine("OutputDirectory")
            appendLine("\t outDir   = $outDir")
            if (autoCreateOutFile) {
                appendLine("\t out      = ${outDir.resolve(outFileName)}")
            } else {
                appendLine("\t out      = <discard, autoCreateOutFile=false>")
            }
            appendLine("\t excelDir = ${outDir.resolve(EXCEL_DIR_NAME)}")
            appendLine("\t dbDir    = ${outDir.resolve(DB_DIR_NAME)}")
            appendLine("\t csvDir   = ${outDir.resolve(CSV_DIR_NAME)}")
            appendLine("\t plotDir  = ${outDir.resolve(PLOT_DIR_NAME)}")
        }
    }

    companion object {
        private const val EXCEL_DIR_NAME = "excelDir"
        private const val DB_DIR_NAME = "dbDir"
        private const val CSV_DIR_NAME = "csvDir"
        private const val PLOT_DIR_NAME = "plotDir"
    }
}
