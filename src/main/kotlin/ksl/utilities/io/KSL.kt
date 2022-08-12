package ksl.utilities.io

import mu.KLoggable

import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

object KSL : KLoggable {

    /**
     * A global logger for logging
     */
    override val logger = logger()

    /**
     * Used to assign unique enum constants
     */
    private var myEnumCounter = 0

    private val myOutputDir = OutputDirectory("kslOutput", "kslOutput.txt")

    /**
     *  Use like System.out, but it goes to a file called jslOutput.txt
     */
    val out = myOutputDir.out

    /**
     *
     * @return the path to the base directory
     */
    val outDir = myOutputDir.outDir

    /**
     *
     * @return the path to the default excel directory
     */
    val excelDir = myOutputDir.excelDir

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