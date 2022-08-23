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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.statistic.WeightedStatistic
import java.nio.file.Path

/** Represents a comma separated value file for replication data
 *
 * SimName, ModelName, ExpName, RepNum, ResponseType, ResponseID, ResponseName, ..
 * then the header from WeightedStatistic.csvStatisticHeader
 *
 * Captures all Response, TWResponse variables, and Counters
 * @param pathToFile the path to the file
 */
class CSVReplicationReport(
    model: Model,
    pathToFile: Path = model.outputDirectory.outDir,
    name: String? = KSLFileUtil.removeLastFileExtension(pathToFile.fileName.toString())
) : CSVReport(model, pathToFile, name) {
    /**
     * @return The number of times afterReplication was called
     */
    var replicationCount = 0
        protected set

    override fun beforeExperiment() {
        super.beforeExperiment()
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

    override fun afterReplication() {
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