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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.Statistic
import java.nio.file.Path

/** Represents a comma separated value file for experiment data (across
 * replication data)
 *
 * SimName, ModelName, ExpName, RepNum, ResponseType, ResponseID, ResponseName, ..
 * then the header from StatisticIfc.csvStatisticHeader
 *
 * Captures all Response, TWResponse variables, and Counters
 * @param model the model for which to create the report
 * @param reportName the name of the report
 * @param directoryPath the path to the directory that will contain the report
 */
class CSVExperimentReport(
    model: Model,
    reportName: String = model.name + "_CSVExperimentReport",
    directoryPath: Path = model.outputDirectory.outDir,
) : CSVReport(model, reportName, directoryPath) {

    override fun writeHeader() {
        if (headerFlag) {
            return
        }
        headerFlag = true
        myWriter.print("SimName,")
        myWriter.print("ModelName,")
        myWriter.print("ExpName,")
        myWriter.print("ResponseType,")
        myWriter.print("ResponseID,")
        myWriter.print("ResponseName,")
        val s = Statistic()
        myWriter.print(s.csvStatisticHeader)
        myWriter.println()
    }

    override fun afterExperiment(modelElement: ModelElement) {
        for (rv in model.responses) {
            if (rv.defaultReportingOption) {
                myWriter.print(model.simulationName)
                myWriter.print(",")
                myWriter.print(model.name)
                myWriter.print(",")
                myWriter.print(model.experimentName)
                myWriter.print(",")
                myWriter.print(rv::class.simpleName + ",")
                myWriter.print(rv.id.toString() + ",")
                myWriter.print(rv.name + ",")
                myWriter.print(rv.acrossReplicationStatistic.csvStatistic)
                myWriter.println()
            }
        }
        for (c in model.counters) {
            if (c.defaultReportingOption) {
                myWriter.print(model.simulationName)
                myWriter.print(",")
                myWriter.print(model.name)
                myWriter.print(",")
                myWriter.print(model.experimentName)
                myWriter.print(",")
                myWriter.print(c::class.simpleName + ",")
                myWriter.print(c.id.toString() + ",")
                myWriter.print(c.name + ",")
                myWriter.print(c.acrossReplicationStatistic.csvStatistic)
                myWriter.println()
            }
        }
    }
}