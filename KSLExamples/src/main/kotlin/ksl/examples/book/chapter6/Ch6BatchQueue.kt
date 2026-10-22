/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.book.chapter6

import ksl.modeling.entity.BatchQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  Example 6.9
 *  This example illustrates how to use a BatchQueue to form batches of entities and
 *  how to split the batch after the batch has been processed.
 */
fun main() {
    val m = Model()
    BatchQueueExample(m)
    m.numberOfReplications = 1
    m.lengthOfReplication = 20.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}

/**
 * Example 6.9 — demonstrates batching with a [BatchQueue]. Parts arrive and wait in the batch queue
 * until three parts are available. The part that completes the batch carries the batch through an
 * oven; the other parts in the batch end their process. After baking, the batch is split: the
 * carrying part activates an inspection process for each of the other parts and then goes to
 * inspection itself.
 */
class BatchQueueExample(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val batchQ: BatchQueue<Part> = BatchQueue(this, defaultBatchSize = 3, name = "OvenBatchQ")
    private val oven: ResourceWithQ = ResourceWithQ(this, "Oven")
    private val inspector: ResourceWithQ = ResourceWithQ(this, "Inspector")
    private val bakeTime = 5.0
    private val inspectionTime = 1.0
    private val timeInSystem = Response(this, "PartTimeInSystem")
    private val tba = ExponentialRV(2.0, 1)
    private val generator = EntityGenerator(::Part, tba, tba)

    private inner class Part : BatchingEntity<Part>() {
        val baking: KSLProcess = process("baking", isDefaultProcess = true) {
            println("$time > part ${this@Part.name} arrives to the batch queue")
            if (waitedForBatch(this@Part, batchQ, batchName = "load")) {
                println("$time > part ${this@Part.name} was placed in a batch and ends its baking process")
                return@process
            }
            val load = this@Part["load"]
            println("$time > part ${this@Part.name} formed the batch [${load.joinToString { it.name }}]")
            use(oven, delayDuration = bakeTime)
            println("$time > part ${this@Part.name} unloads the batch from the oven")
            for (part in load) {
                if (part != this@Part) {
                    activate(part.inspection)
                }
            }
            clearBatch("load")
            use(inspector, delayDuration = inspectionTime)
            println("$time > part ${this@Part.name} completed inspection")
            timeInSystem.value = time - createTime
        }

        val inspection: KSLProcess = process("inspection") {
            use(inspector, delayDuration = inspectionTime)
            println("$time > part ${this@Part.name} completed inspection")
            timeInSystem.value = time - createTime
        }
    }
}
