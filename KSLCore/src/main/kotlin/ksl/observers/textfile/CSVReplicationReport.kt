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
package ksl.observers.textfile

import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.WeightedStatistic
import java.nio.file.Path

/** Represents a comma separated value file for replication data
 *
 * SimName, ModelName, ExpName, RepNum, ResponseType, ResponseID, ResponseName, ..
 * then the header from WeightedStatistic.csvStatisticHeader
 *
 * Captures all Response, TWResponse variables, and Counters
 * @param model the model for which to create the report
 * @param reportName the name of the report
 * @param directoryPath the path to the directory that will contain the report
 */
class CSVReplicationReport(
    model: Model,
    reportName: String = model.name + "_CSVReplicationReport",
    directoryPath: Path = model.outputDirectory.csvDir,
) : CSVReport(model, reportName, directoryPath) {
    /**
     * @return The number of times afterReplication was called
     */
    var replicationCount = 0
        protected set

    override fun beforeExperiment(modelElement: ModelElement) {
        super.beforeExperiment(modelElement)
        replicationCount = 0
    }

    override fun writeHeader() {
        if (headerFlag == true) {
            return
        }
        headerFlag = true
        myWriter.print("SimName,")
        myWriter.print("ModelName,")
        myWriter.print("ExpName,")
        myWriter.print("RepNum,")
        myWriter.print("ResponseType,")
        myWriter.print("ResponseID,")
        myWriter.print("ResponseName,")
        val w = WeightedStatistic()
        myWriter.print(w.csvStatisticHeader)
        myWriter.println()
    }

    private fun writeLine(rv: Response) {
        myWriter.print(model.simulationName)
        myWriter.print(",")
        myWriter.print(model.name)
        myWriter.print(",")
        myWriter.print(model.experimentName)
        myWriter.print(",")
        myWriter.print(replicationCount)
        myWriter.print(",")
        myWriter.print(rv::class.simpleName)
        myWriter.print(",")
        myWriter.print(rv.id)
        myWriter.print(",")
        myWriter.print(rv.name)
        myWriter.print(",")
        myWriter.print(rv.withinReplicationStatistic.csvStatistic)
        myWriter.println()
    }

    private fun writeLine(c: Counter) {
        myWriter.print(model.simulationName)
        myWriter.print(",")
        myWriter.print(model.name)
        myWriter.print(",")
        myWriter.print(model.experimentName)
        myWriter.print(",")
        myWriter.print(replicationCount)
        myWriter.print(",")
        myWriter.print(c::class.simpleName)
        myWriter.print(",")
        myWriter.print(c.id)
        myWriter.print(",")
        myWriter.print(c.name)
        myWriter.print(",")
        myWriter.print(c.name)
        myWriter.print(",")
        myWriter.print(c.value)
        myWriter.println()
    }

    override fun afterReplication(modelElement: ModelElement) {
        replicationCount++
        val rvs = model.responses
        for (rv in rvs) {
            if (rv.defaultReportingOption) {
                writeLine(rv)
            }
        }
        val counters = model.counters
        for (c in counters) {
            if (c.defaultReportingOption) {
                writeLine(c)
            }
        }
    }
}