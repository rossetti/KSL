package ksl.utilities.io

import mu.KotlinLogging
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * This class provides basic context for creating and writing output files.
 * Files and directories created by instances of this class will be relative to
 * the Path supplied at creation.
 *
 * @param outputDirectoryPath the base output directory to use for writing text files relative to this OutputDirectory instance
 * @param outFileName the name of the created text file related to property out
 */
class OutputDirectory(outputDirectoryPath: Path, outFileName: String) {

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
    fun createDirectoryPath(pathToDir: Path) : Path {
        try {
             return Files.createDirectories(pathToDir)
        } catch (e: IOException) {
            logger.error("There was a problem creating the directories for {} used program launch directory", pathToDir)
            return KSLFileUtil.programLaunchDirectory
        }
    }

    /**
     * The path to the default Excel directory, relative to this output directory
     */
    val excelDir: Path = createExcelDirectory()

    private fun createExcelDirectory(): Path {
        try {
            return Files.createDirectories(outDir.resolve("excel"))
        } catch (e: IOException) {
            logger.error("There was a problem creating the directories for {} used program launch directory",
                outDir.resolve("excel"))
            return KSLFileUtil.programLaunchDirectory
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

    /** Makes a Path to the named sub-directory within the base directory
     *
     * @param dirName the name of the sub-directory to create. It must not be null
     * @return a path to the created sub-directory, or the base directory if something went wrong in the creation.
     * Any problems are logged.
     */
    fun createSubDirectory(dirName: String): Path {
        return KSLFileUtil.createSubDirectory(outDir, dirName)
    }

}