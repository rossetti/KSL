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

package ksl.examples.general.processview.waits

import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Signal
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.UniformRV

/**
 *  An assembly cell fed with kits. A kitting station puts a kit into the kit bin every so often. The
 *  assembler takes one kit at a time from the bin and assembles it. While the assembler waits for a kit,
 *  one of three things ends the wait, whichever comes first:
 *  - a kit arrives in the bin, and the assembler assembles it;
 *  - the supervisor recalls the assembler to a rework job, and the assembler does it and comes back;
 *  - the assembler has waited `expediteAfter` minutes, and orders an expedited kit, which arrives in the
 *    bin `expediteLeadTime` later (one at a time).
 *
 *  The wait is one `waitForFirst` on an items trigger for the bin, a trigger for the supervisor's signal,
 *  and a timeout. A recall the supervisor sends while the assembler is busy reaches no one: a `Signal`
 *  wakes only those waiting on it.
 */
class KittingCell(
    parent: ModelElement,
    timeBetweenKits: RVariableIfc = ExponentialRV(10.0, 1),
    assemblyTime: RVariableIfc = UniformRV(6.0, 9.0, 2),
    timeBetweenRecalls: RVariableIfc = ExponentialRV(60.0, 3),
    reworkTime: RVariableIfc = ConstantRV(10.0),
    val expediteAfter: Double = 15.0,
    val expediteLeadTime: Double = 5.0,
    private val trace: Boolean = false,
    name: String? = null
) : ProcessModel(parent, name) {

    private val kitInterval = RandomVariable(this, timeBetweenKits, "TimeBetweenKits")
    private val assembly = RandomVariable(this, assemblyTime, "AssemblyTime")
    private val recallInterval = RandomVariable(this, timeBetweenRecalls, "TimeBetweenRecalls")
    private val rework = RandomVariable(this, reworkTime, "ReworkTime")

    private inner class Kit(val expedited: Boolean) : QObject()

    private val kitBin: BlockingQueue<Kit> = BlockingQueue(this, name = "KitBin")
    private val recall = Signal(this, "Recall")

    private val kitsMade = Counter(this, "KitsMade")
    private val kitsExpedited = Counter(this, "KitsExpedited")
    private val kitsAssembled = Counter(this, "KitsAssembled")
    private val recallsSent = Counter(this, "RecallsSent")
    private val recallsAnswered = Counter(this, "RecallsAnswered")
    private val unaccounted = Response(this, "KitsUnaccounted")

    private var kitInAssembly = 0
    private var expediteOutstanding = false

    private fun log(what: String) {
        if (trace) println(String.format("%8.2f  %s", time, what))
    }

    private inner class KittingStation : Entity() {
        val supply: KSLProcess = process {
            while (true) {
                delay(kitInterval)
                kitsMade.increment()
                log("a kit goes into the bin")
                send(Kit(expedited = false), kitBin)
            }
        }
    }

    private inner class Supervisor : Entity() {
        val oversight: KSLProcess = process {
            while (true) {
                delay(recallInterval)
                recallsSent.increment()
                log("the supervisor calls for rework")
                recall.signal()
            }
        }
    }

    private inner class Expediter : Entity() {
        val delivery: KSLProcess = process {
            delay(expediteLeadTime)
            kitsExpedited.increment()
            expediteOutstanding = false
            log("the expedited kit goes into the bin")
            send(Kit(expedited = true), kitBin)
        }
    }

    private inner class Assembler : Entity() {
        val work: KSLProcess = process {
            while (true) {
                val kitArrives = kitBin.itemsTrigger(amount = 1)
                val calledAway = recall.trigger()
                val result = waitForFirst(kitArrives, calledAway, timeout = expediteAfter)
                when (result.trigger) {
                    kitArrives -> {
                        val kit = result.valueOf(kitArrives).first()
                        log("the assembler takes a${if (kit.expedited) "n expedited" else ""} kit, after waiting ${"%.2f".format(result.timeWaited)}")
                        kitInAssembly = 1
                        delay(assembly)
                        kitInAssembly = 0
                        kitsAssembled.increment()
                    }
                    calledAway -> {
                        recallsAnswered.increment()
                        log("the assembler answers the recall")
                        delay(rework)
                    }
                    null -> {
                        if (!expediteOutstanding) {
                            expediteOutstanding = true
                            log("the assembler has waited ${"%.2f".format(result.timeWaited)} and expedites a kit")
                            activate(Expediter().delivery)
                        }
                    }
                }
            }
        }
    }

    override fun initialize() {
        kitInAssembly = 0
        expediteOutstanding = false
        activate(KittingStation().supply)
        activate(Supervisor().oversight)
        activate(Assembler().work)
    }

    /** Every kit made or expedited is assembled, being assembled, or in the bin. */
    override fun replicationEnded() {
        unaccounted.value = kitsMade.value + kitsExpedited.value - kitsAssembled.value - kitInAssembly - kitBin.channelQ.size
    }
}

/**
 *  First a short run with constant times, whose trace can be followed by hand. Then a long run with random
 *  times, checking that every kit is accounted for and counting the recalls that reached no one.
 */
fun main() {
    println("A short run with constant times: kits every 20 minutes, assembly 8, recalls every 30, rework 10")
    val short = Model("KittingTrace", autoCSVReports = false)
    KittingCell(
        short, timeBetweenKits = ConstantRV(20.0), assemblyTime = ConstantRV(8.0),
        timeBetweenRecalls = ConstantRV(30.0), reworkTime = ConstantRV(10.0), trace = true, name = "Cell"
    )
    short.numberOfReplications = 1
    short.lengthOfReplication = 100.0
    short.simulate()

    println()
    println("A long run with random times")
    val long = Model("Kitting", autoCSVReports = false)
    KittingCell(long, name = "Cell")
    long.numberOfReplications = 20
    long.lengthOfReplication = 10_000.0
    long.simulate()
    val statistics = long.responses.associate { it.name to it.acrossReplicationStatistic } +
            long.counters.associate { it.name to it.acrossReplicationStatistic }
    fun stat(name: String) = statistics.getValue(name)
    println(String.format("%-18s %10s %12s", "per replication", "average", "half-width"))
    for (name in listOf("KitsMade", "KitsExpedited", "KitsAssembled", "RecallsSent", "RecallsAnswered", "KitsUnaccounted")) {
        val s = stat(name)
        println(String.format("%-18s %10.2f %12.2f", name, s.average, s.halfWidth))
    }
    val unaccounted = stat("KitsUnaccounted")
    println()
    println(
        if (unaccounted.min == 0.0 && unaccounted.max == 0.0) "Every kit is accounted for in every replication."
        else "Kits went MISSING."
    )
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 5.")
}
