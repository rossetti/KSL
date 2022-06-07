package ksl.utilities.io

import ksl.utilities.KSLArrays
import ksl.utilities.toCSVString
import mu.KLoggable
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
object KSLFileUtil : KLoggable {
    private var myFileCounter_ = 0

    /**
     *  Use for general logging
     */
    override val logger = logger()

    /**
     * System.out as a PrintWriter
     */
    val SOUT = PrintWriter(System.out)

    /**
     * Returns the directory that the program was launched from on the OS
     * as a string. This call may throw a SecurityException if the system information
     * is not accessible. Uses System property "user.dir"
     *
     * @return the path as a string
     */
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
     * @return the returned PrintWriter, or a PrintWriter wrapping System.out if some problem occurs
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
     * @return the PrintWriter, may be System.out if an IOException occurred
     */
    fun createPrintWriter(file: File): PrintWriter {
        return try {
            PrintWriter(FileWriter(file), true)
        } catch (ex: IOException) {
            val str = "Problem creating PrintWriter for " + file.absolutePath
            logger.error(str, ex)
            PrintWriter(System.out)
        }
    }

    /**
     * Makes the file in the directory that the program launched within
     *
     * @param fileName the name of the file to make
     * @return the created PrintWriter, may be System.out if an IOException occurred
     */
    fun createPrintWriter(fileName: String): PrintWriter {
        return createPrintWriter(programLaunchDirectory.resolve(fileName))
    }

    /**
     * Makes a PrintWriter from the given path, any IOExceptions are caught and logged.
     *
     * @param pathToFile the path to the file that will be underneath the PrintWriter, must not be null
     * @return the returned PrintWriter, or System.out if some IOException occurred
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
     * @return the LogPrintWriter, may be a PrintWriter wrapping System.out if an IOException occurred
     */
    fun createLogPrintWriter(file: File): LogPrintWriter {
        return try {
            LogPrintWriter(FileWriter(file), true)
        } catch (ex: IOException) {
            val str = "Problem creating LogPrintWriter for " + file.absolutePath
            logger.error(str, ex)
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
            logger.error("There was a problem creating the directories for {}", path)
            e.printStackTrace()
        }
        return null
    }

    /**
     * Creates a sub-directory within the supplied main directory.
     *
     * @param mainDir a path to the directory to hold the sub-directory, must not be null
     * @param dirName the name of the sub-directory, must not be null
     * @return the path to the sub-directory, or mainDir, if something went wrong
     */
    fun createSubDirectory(mainDir: Path, dirName: String): Path {
        val newDirPath = mainDir.resolve(dirName)
        return try {
            Files.createDirectories(newDirPath)
        } catch (e: IOException) {
            logger.error("There was a problem creating the sub-directory for {}", newDirPath)
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

//    /**
//     * Uses Desktop.getDesktop() to open the file
//     *
//     * @param file the file
//     * @throws IOException if file cannot be opened
//     */
//    @Throws(IOException::class)
//    fun openFile(file: File?) {
//        if (file == null) {
//            JOptionPane.showMessageDialog(
//                null,
//                "Cannot open the supplied file because it was null",
//                "Warning",
//                JOptionPane.WARNING_MESSAGE
//            )
//            return
//        }
//        if (!file.exists()) {
//            JOptionPane.showMessageDialog(
//                null,
//                "Cannot open the supplied file because it does not exist.",
//                "Warning",
//                JOptionPane.WARNING_MESSAGE
//            )
//            return
//        }
//        if (Desktop.isDesktopSupported()) {
//            Desktop.getDesktop().open(file)
//        } else {
//            JOptionPane.showMessageDialog(
//                null,
//                "Cannot open the supplied file because it \n AWT Desktop is not supported!",
//                "Warning",
//                JOptionPane.WARNING_MESSAGE
//            )
//            return
//        }
//    }

//    /**
//     * Creates a PDF representation of a LaTeX file within the
//     * provided directory with the given name.
//     *
//     * @param pdfcmd   the command for making the pdf within the OS
//     * @param dirname  must not be null
//     * @param filename must not be null, must have .tex extension
//     * @return the process exit value
//     * @throws IOException          if file does not exist or end with .tex
//     * @throws InterruptedException if it was interrupted
//     */
//    @Throws(IOException::class, InterruptedException::class)
//    fun makePDFFromLaTeX(pdfcmd: String?, dirname: String?, filename: String?): Int {
//        requireNotNull(dirname) { "The directory name was null" }
//        requireNotNull(filename) { "The file name was null" }
//        val d = File(dirname)
//        d.mkdir()
//        val f = File(d, filename)
//        if (!f.exists()) {
//            d.delete()
//            throw IOException("The file did not exist")
//        }
//        if (f.length() == 0L) {
//            d.delete()
//            throw IOException("The file was empty")
//        }
//        val fn = f.name
//        val g = fn.split("\\.").toTypedArray()
//        require(g[1] == "tex") { "The file was not a tex file" }
//        val b = ProcessBuilder()
//        b.command(pdfcmd, f.name)
//        b.directory(d)
//        val process = b.start()
//        process.waitFor()
//        return process.exitValue()
//    }
//
//    /**
//     * Creates a PDF representation of a LaTeX file within the
//     * with the given name. Uses pdflatex if it
//     * exists
//     *
//     * @param pdfCmdString must not be null, the appropriate OS system command to convert tex file
//     * @param file         must not be null, must have .tex extension
//     * @return the process exit value
//     * @throws IOException          if file does not exist or end with .tex
//     * @throws InterruptedException if it was interrupted
//     */
//    @Throws(IOException::class, InterruptedException::class)
//    fun makePDFFromLaTeX(pdfCmdString: String, file: File?): Int {
//        Objects.requireNonNull(pdfCmdString, "The latex to pdf command string was null")
//        requireNotNull(file) { "The file was null" }
//        if (!file.exists()) {
//            throw IOException("The file did not exist")
//        }
//        if (file.length() == 0L) {
//            throw IOException("The file was empty")
//        }
//        require(isTexFileName(file.name)) { "The file was not a tex file" }
//        val b = ProcessBuilder()
//        b.command(pdfCmdString, file.name)
//        val process = b.start()
//        process.waitFor()
//        return process.exitValue()
//    }

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
        if (filename == null || filename.isEmpty()) {
            return filename
        }
        val extPattern = "(?<!^)[.]" + if (removeAllExtensions) ".*" else "[^.]*$"
        return filename.replace(extPattern.toRegex(), "")
    }

    /**
     * This method will check for the dot ‘.' occurrence in the given filename.
     * If it exists, then it will find the last position of the dot ‘.'
     * and return the characters after that, the characters after the last dot ‘.' known as the file extension.
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
     * This method will check for the dot ‘.' occurrence in the given filename.
     * If it exists, then it will find the last position of the dot ‘.'
     * and return the characters after that, the characters after the last dot ‘.' known as the file extension.
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
    fun createCSVFileName(name: String?): String {
        return createFileName(name, "csv")
    }

    /**
     * Makes a String that has the form name.txt
     *
     * @param name the name
     * @return the formed String
     */
    fun createTxtFileName(name: String?): String {
        return createFileName(name, "txt")
    }

    /**
     * Makes a String that has the form name.ext
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
            name = "Temp" + myFileCounter_
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
     * @param fileName the name of the file, must not be null, file will appear in JSL.getInstance().getOutDir()
     */
    fun writeToFile(array: DoubleArray, fileName: String, df: DecimalFormat? = null) {
        val pathToFile = KSL.outDir.resolve(fileName)
        writeToFile(array, pathToFile, df)
    }

    /**
     * Writes the data in the array to rows in the file, each row with one data point
     *
     * @param array      the array to write, must not be null
     * @param pathToFile the path to the file, must not be null
     */
    fun writeToFile(array: DoubleArray, pathToFile: Path, df: DecimalFormat? = null) {
        val out = createPrintWriter(pathToFile)
        write(array, out, df)
    }

    /**  Allows writing directly to a known PrintWriter.  Facilitates writing
     * to the file before or after the array is written
     *
     * @param array the array to write, must not be null
     * @param out the PrintWriter to write to, must not be null
     */
    fun write(array: DoubleArray, out: PrintWriter = KSLFileUtil.SOUT, df: DecimalFormat? = null) {
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
    fun write(array: Array<DoubleArray>, out: PrintWriter = KSLFileUtil.SOUT, df: DecimalFormat? = null) {
        for (doubles in array) {
            out.println(toCSVString(doubles, df))
        }
        out.flush()
    }

    /**
     * @param df a format to apply to the values of the array when writing the strings
     * @param array  the array to convert
     * @return a comma delimited string of the array, if empty or null, returns the empty string
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
    fun write(array: IntArray, out: PrintWriter = KSLFileUtil.SOUT) {
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
    fun write(array: Array<IntArray>, out: PrintWriter = KSLFileUtil.SOUT) {
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
}

fun Array<DoubleArray>.write(out: PrintWriter = KSLFileUtil.SOUT, df: DecimalFormat? = null) {
    KSLFileUtil.write(this, out, df)
}

fun Array<DoubleArray>.writeToFile(pathToFile: Path, df: DecimalFormat? = null) {
    KSLFileUtil.writeToFile(this, pathToFile, df)
}

fun Array<DoubleArray>.writeToFile(fileName: String, df: DecimalFormat? = null) {
    KSLFileUtil.writeToFile(this, fileName, df)
}

fun DoubleArray.write(out: PrintWriter = KSLFileUtil.SOUT, df: DecimalFormat? = null) {
    KSLFileUtil.write(this, out, df)
}

fun DoubleArray.writeToFile(pathToFile: Path, df: DecimalFormat? = null) {
    KSLFileUtil.writeToFile(this, pathToFile, df)
}

fun DoubleArray.writeToFile(fileName: String, df: DecimalFormat? = null) {
    KSLFileUtil.writeToFile(this, fileName, df)
}

fun Array<IntArray>.write(out: PrintWriter = KSLFileUtil.SOUT) {
    KSLFileUtil.write(this, out)
}

fun Array<IntArray>.writeToFile(pathToFile: Path) {
    KSLFileUtil.writeToFile(this, pathToFile)
}

fun Array<IntArray>.writeToFile(fileName: String) {
    KSLFileUtil.writeToFile(this, fileName)
}

fun IntArray.write(out: PrintWriter = KSLFileUtil.SOUT) {
    KSLFileUtil.write(this, out)
}

fun IntArray.writeToFile(pathToFile: Path) {
    KSLFileUtil.writeToFile(this, pathToFile)
}

fun IntArray.writeToFile(fileName: String) {
    KSLFileUtil.writeToFile(this, fileName)
}