/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.observers.textfile

import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.utilities.io.KSLFileUtil
import java.io.PrintWriter
import java.nio.file.Path
import java.util.*

/**
 *
 */
abstract class CSVReport(
    model: Model,
    pathToFile: Path = model.outputDirectory.outDir,
    name: String? = KSLFileUtil.removeLastFileExtension(pathToFile.fileName.toString())
) :
    ModelElementObserver<Model>(model, name) {
    var quoteChar = '"'
    var headerFlag = false
    var lineWidth = 300
    protected val myLine: StringBuilder = StringBuilder(lineWidth)
    protected val myWriter: PrintWriter

    init {
        var path = pathToFile
        if (!KSLFileUtil.hasCSVExtension(path)) {
            // need to add csv extension
            path = path.resolve(".csv")
        }
        myWriter = KSLFileUtil.createPrintWriter(path)
    }

    fun close() {
        myWriter.close()
    }

    protected abstract fun writeHeader()

    override fun beforeExperiment() {
        // write header
        writeHeader()
    }
}