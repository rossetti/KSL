/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.observers.textfile

import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSLFileUtil
import java.io.PrintWriter
import java.nio.file.Path

/**
 * @param theModel the model for which to create the report
 * @param reportName the name of the report
 * @param directoryPath the path to the directory that will contain the report
 */
abstract class CSVReport(
    theModel: Model,
    reportName: String = theModel.name + "_CSVReport",
    directoryPath: Path = theModel.outputDirectory.outDir,
) :
    ModelElementObserver(reportName) {
    var quoteChar = '"'
    var headerFlag = false
    var lineWidth = 300
    protected val myLine: StringBuilder = StringBuilder(lineWidth)
    protected val myWriter: PrintWriter
    protected val model = theModel

    init {
        var path = directoryPath
        if (!KSLFileUtil.hasCSVExtension(path)) {
            // need to add csv extension
            path = path.resolve("$reportName.csv")
        } else {
            path = path.resolve(reportName)
        }
        myWriter = KSLFileUtil.createPrintWriter(path)
        model.attachModelElementObserver(this)
    }

    fun close() {
        myWriter.close()
    }

    protected abstract fun writeHeader()

    override fun beforeExperiment(modelElement: ModelElement) {
        // write header
        writeHeader()
    }
}