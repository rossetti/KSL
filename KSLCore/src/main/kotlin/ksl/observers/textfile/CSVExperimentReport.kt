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

import ksl.simulation.Model
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.statistic.Statistic
import java.nio.file.Path

/** Represents a comma separated value file for experiment data (across
 * replication data)
 *
 * SimName, ModelName, ExpName, RepNum, ResponseType, ResponseID, ResponseName, ..
 * then the header from StatisticAccessorIfc.getCSVStatisticHeader()
 *
 * Captures all ResponseVariables, TimeWeighted variables, and Counters
 * @param pathToFile the path to the file, must not be null
 */
class CSVExperimentReport(
    model: Model,
    pathToFile: Path = model.outputDirectory.outDir,
    name: String? = KSLFileUtil.removeLastFileExtension(pathToFile.fileName.toString())
) : CSVReport(model, pathToFile, name) {

    override fun writeHeader() {
        if (headerFlag == true) {
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

    override fun afterExperiment() {
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