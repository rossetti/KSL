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

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Signal
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.TriangularRV

/**
 *  A shuttle stop. Riders arrive at `arrivalRate` per minute and wait for the shuttle, which seats
 *  `capacity`. The shuttle leaves when it is full, or `maxHold` minutes after it began waiting with a rider
 *  aboard, whichever comes first, makes a round trip, and comes back. A rider who has waited longer than
 *  their patience, exponential with mean `meanPatience`, gives up and takes a taxi.
 *
 *  Both parties wait with a deadline: a rider waits to be boarded with `suspendForOrTimeout`, and the shuttle
 *  waits to fill with `waitForOrTimeout`. [ShuttleStopByEvents] is the same system written with events.
 */
class ShuttleStop(
    parent: ModelElement,
    val capacity: Int = 12,
    val maxHold: Double = 10.0,
    arrivalRate: Double = 0.5,
    meanPatience: Double = 30.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 2), "Patience")
    private val roundTrip = RandomVariable(this, TriangularRV(10.0, 15.0, 20.0, 3), "RoundTrip")

    private val waiting = mutableListOf<Rider>()
    private val riderArrived = Signal(this, "RiderArrived")
    private val shuttleFull = Signal(this, "ShuttleFull")

    private val numWaiting = TWResponse(this, "NumWaiting")
    private val waitForShuttle = Response(this, "WaitForShuttle")
    private val tookTaxi = Response(this, "ProbTaxi")
    private val tripLoad = Response(this, "TripLoad")
    private val numFullDepartures = Counter(this, "NumFullDepartures")
    private val numTimedDepartures = Counter(this, "NumTimedDepartures")

    private inner class Rider : Entity() {
        val boarded = Suspension("boarded")

        val trip: KSLProcess = process(isDefaultProcess = true) {
            val myPatience = patience.value
            waiting.add(this@Rider)
            numWaiting.increment()
            riderArrived.signal()
            if (waiting.size >= capacity) shuttleFull.signal()
            val result = suspendForOrTimeout(boarded, timeout = myPatience)
            if (result.timedOut) {
                waiting.remove(this@Rider)
                numWaiting.decrement()
                tookTaxi.value = 1.0
            }
        }
    }

    private inner class Shuttle : Entity() {
        val service: KSLProcess = process {
            while (true) {
                if (waiting.isEmpty()) waitFor(riderArrived)
                if (waiting.size < capacity) waitForOrTimeout(shuttleFull, timeout = maxHold)
                val boarding = waiting.take(capacity)
                if (boarding.isEmpty()) continue        // every rider gave up while the shuttle waited
                for (rider in boarding) {
                    waiting.remove(rider)
                    numWaiting.decrement()
                    waitForShuttle.value = time - rider.createTime
                    tookTaxi.value = 0.0
                    resume(rider.boarded)
                }
                tripLoad.value = boarding.size.toDouble()
                if (boarding.size == capacity) numFullDepartures.increment() else numTimedDepartures.increment()
                delay(roundTrip)
            }
        }
    }

    private val generator = EntityGenerator(::Rider, timeBetweenArrivals, timeBetweenArrivals)

    override fun initialize() {
        waiting.clear()
        activate(Shuttle().service)
    }
}

/**
 *  The shuttle stop of [ShuttleStop], written with events instead of processes. It draws the same random
 *  numbers in the same order, so the two must give identical results.
 */
class ShuttleStopByEvents(
    parent: ModelElement,
    val capacity: Int = 12,
    val maxHold: Double = 10.0,
    arrivalRate: Double = 0.5,
    meanPatience: Double = 30.0,
    name: String? = null
) : ModelElement(parent, name) {

    private val timeBetweenArrivals = RandomVariable(this, ExponentialRV(1.0 / arrivalRate, 1), "TimeBetweenArrivals")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 2), "Patience")
    private val roundTrip = RandomVariable(this, TriangularRV(10.0, 15.0, 20.0, 3), "RoundTrip")

    private inner class Rider(val arrivalTime: Double) {
        var gaveUp: KSLEvent<Rider>? = null
    }

    private val waiting = ArrayDeque<Rider>()
    private var shuttleAtStop = true
    private var holdEnds: KSLEvent<Nothing>? = null

    private val numWaiting = TWResponse(this, "NumWaiting")
    private val waitForShuttle = Response(this, "WaitForShuttle")
    private val tookTaxi = Response(this, "ProbTaxi")
    private val tripLoad = Response(this, "TripLoad")
    private val numFullDepartures = Counter(this, "NumFullDepartures")
    private val numTimedDepartures = Counter(this, "NumTimedDepartures")

    override fun initialize() {
        waiting.clear()
        shuttleAtStop = true
        holdEnds = null
        schedule(::arrival, timeBetweenArrivals)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        val rider = Rider(time)
        rider.gaveUp = schedule(::riderGivesUp, patience, message = rider)
        waiting.addLast(rider)
        numWaiting.increment()
        if (shuttleAtStop) {
            if (waiting.size >= capacity) depart()
            else if (holdEnds == null) holdEnds = schedule(::holdTimeUp, maxHold)
        }
        schedule(::arrival, timeBetweenArrivals)
    }

    private fun riderGivesUp(event: KSLEvent<Rider>) {
        waiting.remove(event.message!!)
        numWaiting.decrement()
        tookTaxi.value = 1.0
    }

    private fun holdTimeUp(event: KSLEvent<Nothing>) {
        holdEnds = null
        if (waiting.isNotEmpty()) depart()
    }

    private fun depart() {
        holdEnds?.let { if (it.isScheduled) it.cancel = true }
        holdEnds = null
        val boarding = waiting.take(capacity)
        for (rider in boarding) {
            waiting.removeFirst()
            // an event due after the end of the replication was never put on the calendar
            rider.gaveUp?.let { if (it.isScheduled) it.cancel = true }
            numWaiting.decrement()
            waitForShuttle.value = time - rider.arrivalTime
            tookTaxi.value = 0.0
        }
        tripLoad.value = boarding.size.toDouble()
        if (boarding.size == capacity) numFullDepartures.increment() else numTimedDepartures.increment()
        shuttleAtStop = false
        schedule(::shuttleReturns, roundTrip)
    }

    private fun shuttleReturns(event: KSLEvent<Nothing>) {
        shuttleAtStop = true
        if (waiting.size >= capacity) depart()
        else if (waiting.isNotEmpty()) holdEnds = schedule(::holdTimeUp, maxHold)
    }
}

/**
 *  Runs the stop written with processes and the stop written with events, and compares every response and
 *  counter they share. They must be identical.
 */
fun main() {
    fun run(build: (Model) -> Unit): Model {
        val m = Model("ShuttleStop", autoCSVReports = false)
        build(m)
        m.numberOfReplications = 20
        m.lengthOfReplication = 10_000.0
        m.lengthOfReplicationWarmUp = 1_000.0
        m.simulate()
        return m
    }
    val byProcesses = run { ShuttleStop(it, name = "Stop") }
    val byEvents = run { ShuttleStopByEvents(it, name = "Stop") }

    fun statistics(m: Model) = m.responses.associate { it.name to it.acrossReplicationStatistic } +
            m.counters.associate { it.name to it.acrossReplicationStatistic }
    val p = statistics(byProcesses)
    val e = statistics(byEvents)
    val shared = listOf("NumWaiting", "WaitForShuttle", "ProbTaxi", "TripLoad", "NumFullDepartures", "NumTimedDepartures")
    println(String.format("%-22s %14s %14s %12s %8s", "response", "by processes", "by events", "half-width", "same"))
    var allSame = true
    for (name in shared) {
        val a = p.getValue(name)
        val b = e.getValue(name)
        val same = a.average == b.average && a.variance == b.variance && a.count == b.count
        allSame = allSame && same
        println(String.format("%-22s %14.4f %14.4f %12.4f %8s", name, a.average, b.average, a.halfWidth, if (same) "yes" else "NO"))
    }
    println()
    println(if (allSame) "The two models agree exactly." else "The two models DIFFER.")
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 4.")
}
