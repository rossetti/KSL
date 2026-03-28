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
import ksl.utilities.KSLArrays
import ksl.utilities.toCSVString
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DecimalFormat
import java.util.*

/**
 * Provides some basic file utilities. Additional utilities can be found in Google Guava and
 * Apache Commons IO.  However, this basic IO provides basic needs without external libraries and
 * is integrated withe KSL functionality.
 */
object KSLFileUtil {
    private var myFileCounter_ = 0

    /**
     *  Use for general logging
     */
    val logger: KLogger = KotlinLogging.logger {}

    /**
     * System.out as a PrintWriter
     */
    val SOUT: PrintWriter = PrintWriter(System.out)

    /**
     * Returns the directory that the program was launched from on the OS
     * as a string. This call may throw a SecurityException if the system information
     * is not accessible. Uses System property "user.dir"
     *
     * @return the path as a string
     */
    @Suppress("unused")
    val programLaunchDirectoryAsString: String
        get() = System.getProperty("user.dir")

    /**
     * Returns the path to the directory that the program was launched from on the OS.
     * This call may throw a SecurityException if the system information
     * is not accessible. Uses System property "user.dir"
     *
     * @return the path to the directory
     */
    val programLaunchDirectory: Path
        get() = Paths.get("").toAbsolutePath()

    /**
     * @param pathToFile the path to the file
     * @return true if the path exists and the extension is csv
     */
    fun hasCSVExtension(pathToFile: Path): Boolean {
        val fromPath = getExtensionFromPath(pathToFile)
        return if (fromPath.isEmpty) {
            false
        } else fromPath.get().equals("csv", ignoreCase = true)
    }

    /**
     * Makes a PrintWriter from the given path, any IOExceptions are caught and logged.
     * The path must be to a file, not a directory.  If the directories that are on the
     * path do not exist, they are created.  If the referenced file exists it is written over.
     *
     * @param pathToFile the path to the file that will be underneath the PrintWriter, must not be null
     * @return the returned PrintWriter, or a PrintWriter wrapping [System.out] if some problem occurs.
     */
    fun createPrintWriter(pathToFile: Path): PrintWriter {
        // make the intermediate directories
        val dir = pathToFile.parent
        createDirectories(dir)
        return createPrintWriter(pathToFile.toFile())
    }

    /**
     * Makes a PrintWriter from the given File. IOExceptions are caught and logged.
     * If the file exists it is written over.
     *
     * @param file the file support the returned PrintWriter, must not be null
     * @return the PrintWriter, may be [System.out] if an IOException occurred.
     */
    fun createPrintWriter(file: File): PrintWriter {
        return try {
            PrintWriter(FileWriter(file), true)
        } catch (ex: IOException) {
            val str = "Problem creating PrintWriter for " + file.absolutePath
            logger.error(ex) { str }
            PrintWriter(System.out)
        }
    }

    /**
     * Makes the file in the directory that the program launched within
     *
     * @param fileName the name of the file to make
     * @return the created PrintWriter, may be [System.out] if an IOException occurred.
     */
    fun createPrintWriter(fileName: String): PrintWriter {
        return createPrintWriter(programLaunchDirectory.resolve(fileName))
    }

    /**
     * Makes a PrintWriter from the given path, any IOExceptions are caught and logged.
     *
     * @param pathToFile the path to the file that will be underneath the PrintWriter, must not be null
     * @return the returned PrintWriter, or [System.out] if some IOException occurred.
     */
    fun createLogPrintWriter(pathToFile: Path): LogPrintWriter {
        // make the intermediate directories
        val dir = pathToFile.parent
        createDirectories(dir)
        return createLogPrintWriter(pathToFile.toFile())
    }

    /**
     * Makes a LogPrintWriter from the given File. IOExceptions are caught and logged.
     * If the file exists it will be written over.
     *
     * @param file the file support the returned PrintWriter, must not be null
     * @return the LogPrintWriter, may be a PrintWriter wrapping [System.out] if an IOException occurred.
     */
    fun createLogPrintWriter(file: File): LogPrintWriter {
        return try {
            LogPrintWriter(FileWriter(file), true)
        } catch (ex: IOException) {
            val str = "Problem creating LogPrintWriter for " + file.absolutePath
            logger.error(ex) { str }
            LogPrintWriter(System.out)
        }
    }

    /**
     * Will throw an IOException if something goes wrong in the creation.
     *
     * @param path the path to the directory to create
     * @return the same path after creating the intermediate directories
     */
    fun createDirectories(path: Path): Path? {
        try {
            return Files.createDirectories(path)
        } catch (e: IOException) {
            logger.error { "There was a problem creating the directories for $path" }
            e.printStackTrace()
        }
        return null
    }

    /**
     * Creates a subdirectory within the supplied main directory.
     *
     * @param mainDir a path to the directory to hold the subdirectory, must not be null
     * @param dirName the name of the subdirectory, must not be null
     * @return the path to the subdirectory, or mainDir, if something went wrong
     */
    fun createSubDirectory(mainDir: Path, dirName: String): Path {
        val newDirPath = mainDir.resolve(dirName)
        return try {
            Files.createDirectories(newDirPath)
        } catch (e: IOException) {
            logger.error { "There was a problem creating the sub-directory for $newDirPath" }
            mainDir
        }
    }

    /**
     * @param pathToFile the path to the file, must not be null and must not be a directory
     * @return the reference to the File
     */
    fun createFile(pathToFile: Path): File {
        require(!Files.isDirectory(pathToFile)) { "The path was a directory not a file!" }
        createDirectories(pathToFile.parent)
        return pathToFile.toFile()
    }

    /**
     * Creates a file in the directory that the program was launched from.
     * Parent directories are created if they do not exist.
     *
     * @param fileName the name of the file to create
     * @return the created File instance
     */
    @Suppress("unused")
    fun createFile(fileName: String): File {
        return createFile(programLaunchDirectory.resolve(fileName))
    }

    /**
     * Creates a file within a specific parent directory.
     * Parent directories are created if they do not exist.
     *
     * @param parentDir the path to the directory that will contain the file
     * @param fileName the name of the file to create
     * @return the created File instance
     */
    @Suppress("unused")
    fun createFile(parentDir: Path, fileName: String): File {
        return createFile(parentDir.resolve(fileName))
    }

    /**
     * Creates a file in the standard KSL output directory.
     * Parent directories are created if they do not exist.
     *
     * @param fileName the name of the file to create
     * @return the created File instance
     */
    @Suppress("unused")
    fun createOutFile(fileName: String): File {
        return createFile(KSL.outDir.resolve(fileName))
    }

    /**
     * Creates a file with a guaranteed extension in the specified parent directory.
     * If the file name is null, a temporary name is generated.
     *
     * @param fileName the desired name of the file (can be null)
     * @param extension the required extension (e.g., "csv", "txt")
     * @param parentDir the directory to place the file in (defaults to KSL.outDir)
     * @return the created File instance
     */
    @Suppress("unused")
    fun createFileWithExtension(
        fileName: String?,
        extension: String?,
        parentDir: Path = KSL.outDir
    ): File {
        val fullFileName = createFileName(fileName, extension)
        return createFile(parentDir.resolve(fullFileName))
    }

    /**
     * @param fileName the string path representation of the file
     * @return the string without the extensions
     */
    fun removeLastFileExtension(fileName: String): String? {
        return removeFileExtension(fileName, false)
    }

    /**
     * @param filename            the string path representation of the file
     * @param removeAllExtensions if true all extensions including the last are removed
     * @return the string without the extensions
     */
    fun removeFileExtension(filename: String?, removeAllExtensions: Boolean): String? {
        if (filename.isNullOrEmpty()) {
            return filename
        }
        val extPattern = "(?<!^)[.]" + if (removeAllExtensions) ".*" else "[^.]*$"
        return filename.replace(extPattern.toRegex(), "")
    }

    /**
     * This method will check for the dot '.' occurrence in the given filename.
     * If it exists, then it will find the last position of the dot '.'
     * and return the characters after that, the characters after the last dot '.' known as the file extension.
     * Special Cases:
     *
     *
     * No extension – this method will return an empty String
     * Only extension – this method will return the String after the dot, e.g. gitignore
     * See www.baeldung.com/java-file-extension
     * Used here to avoid having to use external library
     *
     * @param pathToFile the name of the file that has the extension
     * @return an optional holding the string of the extension or null
     */
    fun getExtensionFromPath(pathToFile: Path): Optional<String> {
        return getExtensionByStringFileName(pathToFile.toAbsolutePath().toString())
    }

    /**
     * This method will check for the dot '.' occurrence in the given filename.
     * If it exists, then it will find the last position of the dot '.'
     * and return the characters after that, the characters after the last dot '.' known as the file extension.
     * Special Cases:
     *
     *
     * No extension – this method will return an empty String
     * Only extension – this method will return the String after the dot, e.g. 'gitignore'
     * See www.baeldung.com/java-file-extension
     * Used here to avoid having to use external library
     *
     * @param filename the name of the file that has the extension
     * @return an optional holding the string of the extension or null
     */
    fun getExtensionByStringFileName(filename: String): Optional<String> {
        return Optional.ofNullable(filename)
            .filter { f: String -> f.contains(".") }
            .map { f: String -> f.substring(filename.lastIndexOf(".") + 1) }
    }

    /**
     * @param fileName the name of the file as a string
     * @return true if the extension for the file is txt or TXT
     */
    @Suppress("unused")
    fun isTextFileName(fileName: String?): Boolean {
        if (fileName == null) {
            return false
        }
        val optionalS = getExtensionByStringFileName(fileName)
        return if (optionalS.isEmpty) {
            false
        } else optionalS.get().equals("txt", ignoreCase = true)
    }

    /**
     * @param pathToFile the path to the file, if null, return false
     * @return true if extension on path is txt
     */
    @Suppress("unused")
    fun isTextFile(pathToFile: Path?): Boolean {
        if (pathToFile == null) {
            return false
        }
        val optionalS = getExtensionFromPath(pathToFile)
        return if (optionalS.isEmpty) {
            false
        } else optionalS.get().equals("txt", ignoreCase = true)
    }

    /**
     * @param fileName the name of the file as a string
     * @return true if the extension for the file is txt or TXT
     */
    @Suppress("unused")
    fun isCSVFileName(fileName: String?): Boolean {
        if (fileName == null) {
            return false
        }
        val optionalS = getExtensionByStringFileName(fileName)
        return if (optionalS.isEmpty) {
            false
        } else optionalS.get().equals("csv", ignoreCase = true)
    }

    /**
     * @param pathToFile the path to the file, if null return false
     * @return true if extension on path is csv
     */
    @Suppress("unused")
    fun isCSVFile(pathToFile: Path?): Boolean {
        if (pathToFile == null) {
            return false
        }
        val optionalS = getExtensionFromPath(pathToFile)
        return if (optionalS.isEmpty) {
            false
        } else optionalS.get().equals("csv", ignoreCase = true)
    }

    /**
     * @param fileName the name of the file as a string
     * @return true if the extension for the file is tex
     */
    @Suppress("unused")
    fun isTexFileName(fileName: String?): Boolean {
        if (fileName == null) {
            return false
        }
        val optionalS = getExtensionByStringFileName(fileName)
        return if (optionalS.isEmpty) {
            false
        } else optionalS.get().equals("tex", ignoreCase = true)
    }

    /**
     * @param pathToFile the path to the file, if null return false
     * @return true if extension on path is tex
     */
    fun isTeXFile(pathToFile: Path?): Boolean {
        if (pathToFile == null) {
            return false
        }
        val optionalS = getExtensionFromPath(pathToFile)
        return if (optionalS.isEmpty) {
            false
        } else optionalS.get().equals("tex", ignoreCase = true)
    }

    /**
     * Makes a String that has the form name.csv
     *
     * @param name the name
     * @return the formed String
     */
    @Suppress("unused")
    fun createCSVFileName(name: String?): String {
        return createFileName(name, "csv")
    }

    /**
     * Makes a String that has the form name.txt
     *
     * @param name the name
     * @return the formed String
     */
    @Suppress("unused")
    fun createTxtFileName(name: String?): String {
        return createFileName(name, "txt")
    }

    /**
     * Makes a String that has the form 'name.ext'
     * If an extension already exists it is replaced.
     *
     * @param theName the name
     * @param theFileExtension  the extension
     * @return the String
     */
    fun createFileName(theName: String?, theFileExtension: String?): String {
        var name = theName
        var ext = theFileExtension
        if (name == null) {
            myFileCounter_ = myFileCounter_ + 1
            name = "Temp$myFileCounter_"
        }
        if (ext == null) {
            ext = "txt"
        }
        val s: String
        val dot = name.lastIndexOf(".")
        s = if (dot == -1) {
            // no period found
            "$name.$ext"
        } else {
            // period found
            name.substring(dot) + ext
        }
        return s
    }

    /**
     *  Deletes the directory represented by the path and all of its contents.
     *  If the path is not a directory, it is deleted as a file.
     */
    @Suppress("unused")
    fun deleteDirectory(pathToDir: Path): Boolean {
        return deleteDirectory(pathToDir.toFile())
    }

    /**
     * Recursively deletes
     *
     * @param directoryToBeDeleted the file reference to the directory to delete
     * @return true if deleted
     */
    fun deleteDirectory(directoryToBeDeleted: File): Boolean {
        val allContents = directoryToBeDeleted.listFiles()
        if (allContents != null) {
            for (file in allContents) {
                deleteDirectory(file)
            }
        }
        return directoryToBeDeleted.delete()
    }

    @Throws(IOException::class)
    private fun copyDirectoryInternal(sourceDirectory: File, destinationDirectory: File) {
        if (!destinationDirectory.exists()) {
            destinationDirectory.mkdir()
        }
        for (f in sourceDirectory.list()!!) {
            copyDirectory(File(sourceDirectory, f), File(destinationDirectory, f))
        }
    }

    /**
     *
     * @param source the source directory as a file, must not be null
     * @param destination the destination directory as a file, must not be null
     * @throws IOException if a problem occurs
     */
    @Throws(IOException::class)
    @Suppress("unused")
    fun copyDirectory(source: Path, destination: Path) {
        copyDirectory(source.toFile(), destination.toFile())
    }

    /**
     *
     * @param source the source directory as a file, must not be null
     * @param destination the destination directory as a file, must not be null
     * @throws IOException if a problem occurs
     */
    @Throws(IOException::class)
    fun copyDirectory(source: File, destination: File) {
        if (source.isDirectory) {
            copyDirectoryInternal(source, destination)
        } else {
            copyFile(source, destination)
        }
    }

    @Throws(IOException::class)
    private fun copyFile(sourceFile: File, destinationFile: File) {
        FileInputStream(sourceFile).use { inStream ->
            FileOutputStream(destinationFile).use { out ->
                val buf = ByteArray(1024)
                var length: Int
                while (inStream.read(buf).also { length = it } > 0) {
                    out.write(buf, 0, length)
                }
            }
        }
    }

    /**
     * Writes the data in the array to rows in the file, each row with one data point
     *
     * @param array    the array to write, must not be null
     * @param fileName the name of the file, must not be null, file will appear in KSL.outDir
     */
    fun writeToFile(array: DoubleArray, fileName: String, df: DecimalFormat? = null) : Path {
        val pathToFile = KSL.outDir.resolve(fileName)
        return writeToFile(array, pathToFile, df)
    }

    /**
     * Writes the data in the array to rows in the file, each row with one data point
     *
     * @param array      the array to write, must not be null
     * @param pathToFile the path to the file, must not be null
     */
    fun writeToFile(array: DoubleArray, pathToFile: Path, df: DecimalFormat? = null) : Path {
        val out = createPrintWriter(pathToFile)
        write(array, out, df)
        return pathToFile
    }

    /**  Allows writing directly to a known PrintWriter.  Facilitates writing
     * to the file before or after the array is written
     *
     * @param array the array to write, must not be null
     * @param out the PrintWriter to write to, must not be null
     */
    fun write(array: DoubleArray, out: PrintWriter = SOUT, df: DecimalFormat? = null) {
        for (x in array) {
            if (df == null){
                out.println(x)
            } else {
                out.println(df.format(x))
            }
        }
        out.flush()
    }

    /**
     * Writes the data in the array to rows in the file, each element in a row is
     * separated by a comma
     *
     * @param array    the array to write, must not be null
     * @param fileName the name of the file, must not be null, file will appear in JSL.getInstance().getOutDir()
     */
    fun writeToFile(array: Array<DoubleArray>, fileName: String, df: DecimalFormat? = null) {
        val pathToFile = KSL.outDir.resolve(fileName)
        writeToFile(array, pathToFile, df)
    }

    /**
     * Writes the data in the array to rows in the file, each element in a row is
     * separated by a comma
     *
     * @param array      the array to write, must not be null
     * @param pathToFile the path to the file, must not be null
     */
    fun writeToFile(array: Array<DoubleArray>, pathToFile: Path, df: DecimalFormat? = null) {
        val out = createPrintWriter(pathToFile)
        write(array, out, df)
    }

    /**  Allows writing directly to a known PrintWriter.  Facilitates writing
     * to the file before or after the array is written
     *
     * @param array the array to write, must not be null
     * @param out the PrintWriter to write to, must not be null
     */
    fun write(array: Array<DoubleArray>, out: PrintWriter = SOUT, df: DecimalFormat? = null) {
        for (doubles in array) {
            out.println(toCSVString(doubles, df))
        }
        out.flush()
    }

    /**  Allows writing directly to a known PrintWriter.  Facilitates writing
     * to the file before or after the list is written
     *
     * @param list the array to write, must not be null
     * @param out the PrintWriter to write to, must not be null
     */
    fun write(list: List<DoubleArray>, out: PrintWriter = SOUT, df: DecimalFormat? = null) {
        for (doubles in list) {
            out.println(toCSVString(doubles, df))
        }
        out.flush()
    }

    /**
     * @param df a format to apply to the values of the array when writing the strings
     * @param array  the array to convert
     * @return a comma-delimited string of the array, if empty or null, returns the empty string
     */
    fun toCSVString(array: DoubleArray, df: DecimalFormat? = null): String {
        if (array.isEmpty()) {
            return ""
        }
        val joiner = StringJoiner(", ")
        for (i in array.indices) {
            if (df == null) {
                joiner.add(array[i].toString())
            } else {
                joiner.add(df.format(array[i]))
            }
        }
        return joiner.toString()
    }

    /**
     * Writes the data in the array to rows in the file, each row with one data point
     *
     * @param array    the array to write, must not be null
     * @param fileName the name of the file, must not be null, file will appear in JSL.getInstance().getOutDir()
     */
    fun writeToFile(array: IntArray, fileName: String) {
        val pathToFile = KSL.outDir.resolve(fileName)
        writeToFile(array, pathToFile)
    }

    /**
     * Writes the data in the array to rows in the file, each row with one data point
     *
     * @param array      the array to write, must not be null
     * @param pathToFile the path to the file, must not be null
     */
    fun writeToFile(array: IntArray, pathToFile: Path) {
        val out = createPrintWriter(pathToFile)
        write(array, out)
    }

    /**  Allows writing directly to a known PrintWriter.  Facilitates writing
     * to the file before or after the array is written
     *
     * @param array the array to write, must not be null
     * @param out the PrintWriter to write to, must not be null
     */
    fun write(array: IntArray, out: PrintWriter = SOUT) {
        for (x in array) {
            out.println(x)
        }
        out.flush()
    }

    /**
     * Writes the data in the array to rows in the file, each element in a row is
     * separated by a comma
     *
     * @param array    the array to write, must not be null
     * @param fileName the name of the file, must not be null, file will appear in JSL.getInstance().getOutDir()
     */
    fun writeToFile(array: Array<IntArray>, fileName: String) {
        val pathToFile = KSL.outDir.resolve(fileName)
        writeToFile(array, pathToFile)
    }

    /**
     * Writes the data in the array to rows in the file, each element in a row is
     * separated by a comma
     *
     * @param array      the array to write, must not be null
     * @param pathToFile the path to the file, must not be null
     */
    fun writeToFile(array: Array<IntArray>, pathToFile: Path) {
        val out = createPrintWriter(pathToFile)
        write(array, out)
    }

    /**  Allows writing directly to a known PrintWriter.  Facilitates writing
     * to the file before or after the array is written
     *
     * @param array the array to write, must not be null
     * @param out the PrintWriter to write to, must not be null
     */
    fun write(array: Array<IntArray>, out: PrintWriter = SOUT) {
        for (ints in array) {
            out.println(ints.toCSVString())
        }
        out.flush()
    }

    /**
     * Assumes that the file holds doubles with each value on a different line
     * 1.0
     * 4.0
     * 2.0
     * etc
     *
     * @param pathToFile the path to a file holding the data
     * @return the data as an array
     */
    @Suppress("unused")
    fun scanToArray(pathToFile: Path): DoubleArray {
        try {
            Scanner(pathToFile.toFile()).use { scanner ->
                val list = ArrayList<Double>()
                while (scanner.hasNextDouble()) {
                    list.add(scanner.nextDouble())
                }
                return KSLArrays.toPrimitives(list.toTypedArray())
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        return DoubleArray(0)
    }

    /**
     *  Opens the file in the browser.
     *  The file must be an HTML file and the system must have a default browser configured.
     *  If the file is not an HTML file or if there is no default browser, an IOException may be thrown.
      * @param file the HTML file to open, must not be null
     */
    fun openInBrowser(file: File) {
        val desktop = Desktop.getDesktop()
        desktop.browse(file.toURI())
    }

    /**
     *  Opens the HTML file in the browser.  The file is created in the specified directory with the given file name.
     *  The file will have a .html extension. If the directory does not exist, it will be created.
      * @param fileName the name of the file, must not be null
     * @param html the HTML string to write to the file, must not be null
     * @param dir the directory to create the file in, defaults to KSL.outDir, must not be null
     * @return the created temporary HTML file
     */
    fun openInBrowser(fileName: String, html: String, dir: Path = KSL.outDir): File {
        val file = createTemporaryHTMLFile(fileName, dir)
        FileWriter(file).use {
            it.write(html)
        }
        openInBrowser(file)
        return file
    }

    /**
     * Creates a temporary HTML file in the specified directory with the given file name.
     * The file will have a .html extension.
     * If the directory does not exist, it will be created.
     * @param fileName the name of the file, must not be null
     * @param dir the directory to create the file in, defaults to KSL.outDir, must not be null
     * @return the created temporary HTML file
     */
    fun createTemporaryHTMLFile(fileName: String, dir: Path = KSL.outDir): File {
        val tmpDir = File(dir.toString())
        if (!tmpDir.exists()) {
            tmpDir.mkdir()
        }
        return File.createTempFile(fileName, ".html", tmpDir)
    }

    /**
     * Opens a file dialog to select a file to open.
     * The dialog will open in the directory that the program was launched from.
     * @return the selected file or null if no file was selected
     */
    @Suppress("unused")
    fun chooseFile(): File? {
        val dialog = FileDialog(null as Frame?, "Select File to Open")
        dialog.directory = this.programLaunchDirectory.toString()
        dialog.mode = FileDialog.LOAD
        dialog.isVisible = true

        val fileStr: String? = dialog.file
        val dirStr: String? = dialog.directory
        if (fileStr == null) {
            return null
        }
        if (dirStr == null) {
            return null
        }
        // both are not null
        val dir = Paths.get(dirStr)
        return dir.resolve(fileStr).toFile()
    }
}

/**
 *  Wraps String.format() to print a formatted string.
 *  The [fmt] string must be valid for String.format()
 */
@Suppress("unused")
fun printf(fmt:String, vararg args: Any?){
    print(String.format(fmt, args))
}

/**
 *  Writes the list of double arrays to the PrintWriter, each array on a new line,
 *  and each element in the array separated by a comma.
 *  @param df a format to apply to the values of the array when writing the strings
 *  @param out the PrintWriter to write to, must not be null
 */
@Suppress("unused")
fun List<DoubleArray>.write(out: PrintWriter = KSLFileUtil.SOUT, df: DecimalFormat? = null) {
    KSLFileUtil.write(this, out, df)
}

/**
 *  Writes the array of double arrays to the PrintWriter, each array on a new line,
 *  and each element in the array separated by a comma.
 *  @param df a format to apply to the values of the array when writing the strings
 *  @param out the PrintWriter to write to, must not be null
 */
fun Array<DoubleArray>.write(out: PrintWriter = KSLFileUtil.SOUT, df: DecimalFormat? = null) {
    KSLFileUtil.write(this, out, df)
}

/**
 *  Writes the array of double arrays to [KSLFileUtil.SOUT], each array on a new line,
 *  and each element in the array separated by a comma.
 *  @param df a format to apply to the values of the array when writing the strings
 */
fun Array<DoubleArray>.print(df: DecimalFormat? = null) {
    KSLFileUtil.write(this, KSLFileUtil.SOUT, df)
}

/**
 *  Writes the array of double arrays to a file, each array on a new line,
 *  and each element in the array separated by a comma.
 *  @param df a format to apply to the values of the array when writing the strings
 *  @param pathToFile the path to the file, must not be null
 */
@Suppress("unused")
fun Array<DoubleArray>.writeToFile(pathToFile: Path, df: DecimalFormat? = null) {
    KSLFileUtil.writeToFile(this, pathToFile, df)
}

/**
 *  Writes the array to a file. Each element in the array is on a new line.
 *  @param df a format to apply to the values of the array when writing the strings
 *  @param fileName the name of the file, must not be null, file will appear in KSL.getInstance().getOutDir()
 */
@Suppress("unused")
fun Array<DoubleArray>.writeToFile(fileName: String, df: DecimalFormat? = null) {
    KSLFileUtil.writeToFile(this, fileName, df)
}

/**
 * Writes the array of doubles to the PrintWriter, each element on a new line.
 *  @param df a format to apply to the values of the array when writing the strings
 *  @param out the PrintWriter to write to, must not be null. If not supplied, writes to [KSLFileUtil.SOUT]
 */
@Suppress("unused")
fun DoubleArray.write(out: PrintWriter = KSLFileUtil.SOUT, df: DecimalFormat? = null) {
    KSLFileUtil.write(this, out, df)
}

/**
 * Writes the array of doubles to a file, each element on a new line.
 *  @param df a format to apply to the values of the array when writing the strings
 *  @param pathToFile the path to the file, must not be null.
 */
@Suppress("unused")
fun DoubleArray.writeToFile(pathToFile: Path, df: DecimalFormat? = null) {
    KSLFileUtil.writeToFile(this, pathToFile, df)
}

/**
 * Writes the array of doubles to a file, each element on a new line.
 *  @param df a format to apply to the values of the array when writing the strings
 *  @param fileName the name of the file, must not be null.
 */
@Suppress("unused")
fun DoubleArray.writeToFile(fileName: String, df: DecimalFormat? = null) {
    KSLFileUtil.writeToFile(this, fileName, df)
}

/**
 *  Writes the array of int arrays to the PrintWriter, each array on a new line,
 *  and each element in the array separated by a comma.
 *  @param out the PrintWriter to write to, must not be null. If not supplied, writes to [KSLFileUtil.SOUT]
 */
fun Array<IntArray>.write(out: PrintWriter = KSLFileUtil.SOUT) {
    KSLFileUtil.write(this, out)
}

/**
 *  Writes the array of int arrays to a file, each array on a new line,
 *  and each element in the array separated by a comma.
 *  @param pathToFile the path to the file, must not be null
 */
@Suppress("unused")
fun Array<IntArray>.writeToFile(pathToFile: Path) {
    KSLFileUtil.writeToFile(this, pathToFile)
}

/**
 *  Writes the array of int arrays to a file, each array on a new line,
 *  and each element in the array separated by a comma.
 *  @param fileName the name of the file, must not be null.
 */
@Suppress("unused")
fun Array<IntArray>.writeToFile(fileName: String) {
    KSLFileUtil.writeToFile(this, fileName)
}

/**
 * Writes the array of ints to the PrintWriter, each element on a new line.
 *  @param out the PrintWriter to write to, must not be null. If not supplied, writes to [KSLFileUtil.SOUT]
 */
fun IntArray.write(out: PrintWriter = KSLFileUtil.SOUT) {
    KSLFileUtil.write(this, out)
}

/**
 * Writes the array of ints to a file, each element on a new line.
 *  @param pathToFile the path to the file, must not be null
 */
@Suppress("unused")
fun IntArray.writeToFile(pathToFile: Path) {
    KSLFileUtil.writeToFile(this, pathToFile)
}

/**
 * Writes the array of ints to a file, each element on a new line.
 *  @param fileName the name of the file, must not be null.
 */
@Suppress("unused")
fun IntArray.writeToFile(fileName: String) {
    KSLFileUtil.writeToFile(this, fileName)
}