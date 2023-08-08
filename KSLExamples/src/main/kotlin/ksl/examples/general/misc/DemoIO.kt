/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.misc

import ksl.utilities.io.KSL
import ksl.utilities.io.OutputDirectory
import java.nio.file.Paths

fun main(){
    demoOutputDirectory()
    demoKSLClass()
}

fun demoOutputDirectory(){
    // get the working directory
    val path = Paths.get("").toAbsolutePath()
    println("Working Directory = $path")
    // creates a directory called TestOutputDir in the current working directory
    // Creates subdirectories: csvDir, dbDir, excelDir and file out.txt
    val outDir = OutputDirectory(path.resolve("TestOutputDir"))
    // write to the default file
    outDir.out.println("Use out property to write out text to a file.")
    // Creates a PrintWriter (and file) to write to within TestOutputDir
    val pw = outDir.createPrintWriter("PW_File.txt")
    pw.println("Hello, World")
    val subDir = outDir.createSubDirectory("SubDir")
    println(outDir)
}

fun demoKSLClass(){
    // use KSL like you use OutputDirectory but with some added functionality
    // The PW_File.txt file with be within the kslOutput directory within the working directory
    val pw = KSL.createPrintWriter("PW_File.txt")
    pw.println("Hello, World!")
    // Creates subdirectory SubDir within the kslOutput directory
    KSL.createSubDirectory("SubDir")
    // use KSL.out to write to kslOutput.txt
    KSL.out.println("Information written into kslOutput.txt")
    println(KSL)
    // KSL also has logger. This logs to logs/ksl.log
    KSL.logger.info { "This is an informational log comment!" }
//    KSL.logger2.info { "This is a second informational log comment!" }
}