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

/**
 * @param model the model for which to create the report
 * @param reportName the name of the report
 * @param directoryPath the path to the directory that will contain the report
 */
abstract class CSVReport(
    model: Model,
    reportName: String = model.name + "_CSVReport",
    directoryPath: Path = model.outputDirectory.outDir,
) :
    ModelElementObserver<Model>(model, reportName) {
    var quoteChar = '"'
    var headerFlag = false
    var lineWidth = 300
    protected val myLine: StringBuilder = StringBuilder(lineWidth)
    protected val myWriter: PrintWriter

    init {
        var path = directoryPath
        if (!KSLFileUtil.hasCSVExtension(path)) {
            // need to add csv extension
            path = path.resolve("$reportName.csv")
        } else {
            path = path.resolve(reportName)
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