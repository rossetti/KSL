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
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.StatisticIfc

/** The colour of a part. */
enum class Colour { RED, BLUE }

/** How the bin's parts are taken. */
enum class Picking {
    /** One part at a time, the first of a red part or a blue part: two item triggers in one wait. */
    TWO_TRIGGERS,
    /** One part at a time, of either colour: one request for any part. */
    ONE_REQUEST,
    /** Every part of one colour at once, the first colour to come: two any-items triggers in one wait. */
    BATCHES
}

/**
 *  A bin of red and blue parts. Red parts come every `meanBetweenRed` minutes on average and blue parts every
 *  `meanBetweenBlue`, both exponential, into one blocking queue. A picker takes the parts as [Picking] says and
 *  works on each part, or each batch, for an exponential time with mean `meanWork`. A picker of one part at a
 *  time that waits `patience` minutes with no part tidies the cell for `tidyTime` minutes and comes back. With `firstFillable` the bin serves the first
 *  request it can fill; without it, only the request at the head of its request queue.
 */
class TwoColourBin(
    parent: ModelElement,
    val picking: Picking = Picking.TWO_TRIGGERS,
    firstFillable: Boolean = true,
    val meanBetweenRed: Double = 20.0,
    val meanBetweenBlue: Double = 30.0,
    val meanWork: Double = 9.0,
    val patience: Double = Double.POSITIVE_INFINITY,
    val tidyTime: Double = 5.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val timeBetweenRed = RandomVariable(this, ExponentialRV(meanBetweenRed, 1), "TimeBetweenRed")
    private val timeBetweenBlue = RandomVariable(this, ExponentialRV(meanBetweenBlue, 2), "TimeBetweenBlue")
    private val work = RandomVariable(this, ExponentialRV(meanWork, 3), "Work")

    private inner class Part(val colour: Colour) : QObject()

    private val bin: BlockingQueue<Part> = BlockingQueue(this, name = "Bin")

    init {
        if (firstFillable) bin.receiverRequestSelector = bin.FirstFillableRequest()
    }

    /** The time-average number of parts in the bin. */
    val numInBin get() = bin.channelQ.numInQ
    /** The time parts wait in the bin. */
    val timeInBin get() = bin.channelQ.timeInQ

    /** The parts taken from the bin. */
    val partsTaken = Counter(this, "PartsTaken")
    /** The times the picker tidied the cell. */
    val timesTidied = Counter(this, "TimesTidied")
    /** The parts that arrived and were neither taken nor left in the bin, each replication: none. */
    val unaccounted = Response(this, "PartsUnaccounted")
    /** The batches that left a part of their colour in the bin, each replication: none. */
    val incompleteBatches = Response(this, "IncompleteBatches")
    /** The number of parts in a batch. */
    val batchSize = Response(this, "BatchSize")

    // counts by colour kept from the start of the replication, for the accounting at its end
    private val arrived = IntArray(Colour.entries.size)
    private val taken = IntArray(Colour.entries.size)
    private var incomplete = 0

    /** The parts of [colour] that should be in the bin, by the model's own counts. */
    private fun inBin(colour: Colour) = arrived[colour.ordinal] - taken[colour.ordinal]

    private inner class Supplier(val colour: Colour, val timeBetween: RandomVariable) : Entity() {
        val supply: KSLProcess = process {
            while (true) {
                delay(timeBetween)
                arrived[colour.ordinal]++
                send(Part(colour), bin)
            }
        }
    }

    private fun took(parts: List<Part>) {
        for (p in parts) taken[p.colour.ordinal]++
        partsTaken.increment(parts.size.toDouble())
    }

    private inner class Picker : Entity() {
        val picking: KSLProcess = process {
            while (true) {
                val red = bin.itemsTrigger(predicate = { it.colour == Colour.RED })
                val blue = bin.itemsTrigger(predicate = { it.colour == Colour.BLUE })
                val result = waitForFirst(red, blue, timeout = patience)
                if (result.timedOut) {
                    timesTidied.increment()
                    delay(tidyTime)
                    continue
                }
                took(if (result.firedBy(red)) result.valueOf(red) else result.valueOf(blue))
                delay(work)
            }
        }
    }

    private inner class AnyPartPicker : Entity() {
        val picking: KSLProcess = process {
            while (true) {
                val result = waitForItemsOrTimeout(bin, timeout = patience)
                if (result.timedOut) {
                    timesTidied.increment()
                    delay(tidyTime)
                    continue
                }
                took(result.value)
                delay(work)
            }
        }
    }

    private inner class Painter : Entity() {
        val painting: KSLProcess = process {
            while (true) {
                val red = bin.anyItemsTrigger(predicate = { it.colour == Colour.RED })
                val blue = bin.anyItemsTrigger(predicate = { it.colour == Colour.BLUE })
                val result = waitForFirst(red, blue)
                val batch = if (result.firedBy(red)) result.valueOf(red) else result.valueOf(blue)
                took(batch)
                // the batch took every part of its colour: none of it is left
                if (inBin(batch.first().colour) != 0) incomplete++
                batchSize.value = batch.size.toDouble()
                delay(work)
            }
        }
    }

    override fun initialize() {
        arrived.fill(0)
        taken.fill(0)
        incomplete = 0
        activate(Supplier(Colour.RED, timeBetweenRed).supply)
        activate(Supplier(Colour.BLUE, timeBetweenBlue).supply)
        when (picking) {
            Picking.TWO_TRIGGERS -> activate(Picker().picking)
            Picking.ONE_REQUEST -> activate(AnyPartPicker().picking)
            Picking.BATCHES -> activate(Painter().painting)
        }
    }

    override fun replicationEnded() {
        unaccounted.value = (Colour.entries.sumOf { inBin(it) } - bin.channelQ.size).toDouble()
        incompleteBatches.value = incomplete.toDouble()
    }
}

/**
 *  Runs the picker with each request selector against the exact M/M/1 values, the picker with a time limit
 *  written two ways, and the painter, with 99% confidence intervals from the replications.
 */
fun main() {
    fun run(build: (Model) -> TwoColourBin): TwoColourBin {
        val m = Model("TwoColourBin", autoCSVReports = false)
        val b = build(m)
        m.numberOfReplications = 20
        m.lengthOfReplication = 100_000.0
        m.lengthOfReplicationWarmUp = 10_000.0
        m.simulate()
        return b
    }
    val first = run { TwoColourBin(it, name = "Cell") }
    // one server, the picker, for one stream of parts at the two colours' combined rate
    val lambda = 1.0 / first.meanBetweenRed + 1.0 / first.meanBetweenBlue
    val rho = lambda * first.meanWork
    val exactNumber = rho * rho / (1.0 - rho)
    val exactTime = exactNumber / lambda
    println("A part of either colour, FirstFillableRequest, against the exact M/M/1 values, 99% confidence intervals")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareBin("mean parts in the bin", first.numInBin.acrossReplicationStatistic, exactNumber)
    compareBin("mean time in the bin", first.timeInBin.acrossReplicationStatistic, exactTime)

    val head = run { TwoColourBin(it, firstFillable = false, name = "Cell") }
    println()
    println("The same with the default selector, which serves only the request at the head")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareBin("mean parts in the bin", head.numInBin.acrossReplicationStatistic, exactNumber)
    compareBin("mean time in the bin", head.timeInBin.acrossReplicationStatistic, exactTime)

    val twoTriggers = run { TwoColourBin(it, patience = 10.0, name = "Cell") }
    val oneRequest = run { TwoColourBin(it, picking = Picking.ONE_REQUEST, patience = 10.0, name = "Cell") }
    println()
    println("Waiting at most 10 minutes for a part, then tidying for 5: two triggers against one request")
    println(String.format("%-28s %14s %14s %6s", "statistic", "two triggers", "one request", "same"))
    for ((label, a, b) in listOf(
        Triple("mean parts in the bin", twoTriggers.numInBin.acrossReplicationStatistic, oneRequest.numInBin.acrossReplicationStatistic),
        Triple("parts taken", twoTriggers.partsTaken.acrossReplicationStatistic, oneRequest.partsTaken.acrossReplicationStatistic),
        Triple("times tidied", twoTriggers.timesTidied.acrossReplicationStatistic, oneRequest.timesTidied.acrossReplicationStatistic)
    )) {
        val same = a.average == b.average && a.variance == b.variance
        println(String.format("%-28s %14.4f %14.4f %6s", label, a.average, b.average, if (same) "yes" else "NO"))
    }

    val painter = run { TwoColourBin(it, picking = Picking.BATCHES, name = "Cell") }
    println()
    println("A painter taking every part of one colour at once")
    println(String.format("%-28s %10.4f", "mean batch size", painter.batchSize.acrossReplicationStatistic.average))
    println(String.format("%-28s %10.4f", "mean parts in the bin", painter.numInBin.acrossReplicationStatistic.average))
    println(String.format("%-28s %10.0f", "most parts unaccounted", painter.unaccounted.acrossReplicationStatistic.max))
    println(String.format("%-28s %10.0f", "most incomplete batches", painter.incompleteBatches.acrossReplicationStatistic.max))
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 7.")
}

private fun compareBin(label: String, stat: StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-28s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}
