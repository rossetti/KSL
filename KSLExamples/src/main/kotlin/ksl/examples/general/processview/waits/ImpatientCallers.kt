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
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A call centre whose callers hang up when they have waited too long: `numAgents` agents answer calls
 *  that arrive at `arrivalRate` per minute, each call takes an exponential time with mean `meanServiceTime`,
 *  and each caller waits at most an exponential time with mean `meanPatience` before hanging up. A caller
 *  waits with `seizeOrTimeout`, whose timeout is that caller's patience.
 *
 *  With exponential times this is the M/M/c+M queue (Erlang-A), whose answers are known exactly; see
 *  [ErlangA].
 */
class CallCenter(
    parent: ModelElement,
    numAgents: Int = 10,
    val arrivalRate: Double = 2.4,
    meanServiceTime: Double = 4.0,
    meanPatience: Double = 3.0,
    recordRenegedWait: Boolean = true,
    name: String? = null
) : ProcessModel(parent, name) {

    private val agents = ResourceWithQ(this, "Agents", capacity = numAgents)
    private val timeBetweenCalls = ExponentialRV(1.0 / arrivalRate, 1)
    private val serviceTime = RandomVariable(this, ExponentialRV(meanServiceTime, 2), "ServiceTime")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 3), "Patience")
    private val recordAbandonedWaits = recordRenegedWait

    private val abandoned = Response(this, "ProbAbandon")
    private val numAbandoned = Counter(this, "NumAbandoned")
    private val numServed = Counter(this, "NumServed")

    /** The proportion of callers who hang up. */
    val probAbandon get() = abandoned
    /** The time-average number of callers waiting. */
    val numWaiting get() = agents.waitingQ.numInQ
    /** The time callers wait, for every caller or only those answered: see [recordRenegedWait]. */
    val timeWaiting get() = agents.waitingQ.timeInQ

    private inner class Caller : Entity() {
        val call: KSLProcess = process(isDefaultProcess = true) {
            val result = seizeOrTimeout(
                agents,
                timeout = patience.value,
                recordRenegedWait = recordAbandonedWaits
            )
            if (result.timedOut) {
                abandoned.value = 1.0
                numAbandoned.increment()
                return@process
            }
            abandoned.value = 0.0
            delay(serviceTime)
            release(result.value)
            numServed.increment()
        }
    }

    private val generator = EntityGenerator(::Caller, timeBetweenCalls, timeBetweenCalls)
}

/**
 *  The exact answers for the M/M/c+M queue: `c` servers, Poisson arrivals at rate `lambda`, exponential
 *  service at rate `mu` per server, and exponential patience at rate `theta` per waiting customer.
 *
 *  The number in the system is a birth-death process: it rises at rate `lambda` and falls at rate
 *  `min(n, c) mu + max(n - c, 0) theta`, since each of the `n - c` waiting customers abandons at rate `theta`.
 *  Its stationary distribution gives the mean number waiting, `Lq`. Customers abandon at total rate
 *  `theta Lq`, so the probability that a customer abandons is `theta Lq / lambda`, and by Little's law the mean
 *  wait of all customers, served or not, is `Lq / lambda`.
 */
class ErlangA(val lambda: Double, val mu: Double, val theta: Double, val c: Int) {

    /** The mean number of customers waiting. */
    val meanNumberWaiting: Double

    init {
        require(lambda > 0.0 && mu > 0.0 && theta > 0.0 && c >= 1) { "rates must be > 0 and c >= 1" }
        // p(n) is proportional to the product of birth over death rates up to n; stop when it is negligible
        var term = 1.0
        var total = 1.0
        var waiting = 0.0
        var n = 0
        while (true) {
            n++
            term *= lambda / (minOf(n, c) * mu + maxOf(n - c, 0) * theta)
            total += term
            waiting += maxOf(n - c, 0) * term
            if (n > c && term < 1.0e-16 * total) break
        }
        meanNumberWaiting = waiting / total
    }

    /** The probability that a customer abandons. */
    val probAbandon: Double get() = theta * meanNumberWaiting / lambda

    /** The mean time in queue of all customers, those who abandon included. */
    val meanWait: Double get() = meanNumberWaiting / lambda
}

/**
 *  Runs the call centre and sets each estimate beside its exact value, with a 99% confidence interval
 *  from the replications. Then runs it again without recording the abandoned callers' waits, and checks
 *  Little's law both times: the mean number waiting must equal the arrival rate times the mean wait of
 *  every arrival.
 */
fun main() {
    val exact = ErlangA(lambda = 2.4, mu = 1.0 / 4.0, theta = 1.0 / 3.0, c = 10)

    val model = Model("ImpatientCallers", autoCSVReports = false)
    val center = CallCenter(model, name = "CallCenter")
    model.numberOfReplications = 20
    model.lengthOfReplication = 20_000.0
    model.lengthOfReplicationWarmUp = 2_000.0
    model.simulate()

    println("The call centre against the M/M/c+M (Erlang-A) answers, 99% confidence intervals")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compare("probability of abandoning", center.probAbandon.acrossReplicationStatistic, exact.probAbandon)
    compare("mean number waiting", center.numWaiting.acrossReplicationStatistic, exact.meanNumberWaiting)
    compare("mean wait, all callers", center.timeWaiting.acrossReplicationStatistic, exact.meanWait)

    val notRecorded = Model("ImpatientCallersWaitsOfServedOnly", autoCSVReports = false)
    val centerServedOnly = CallCenter(notRecorded, recordRenegedWait = false, name = "CallCenter")
    notRecorded.numberOfReplications = 20
    notRecorded.lengthOfReplication = 20_000.0
    notRecorded.lengthOfReplicationWarmUp = 2_000.0
    notRecorded.simulate()

    println()
    println("Little's law, L = lambda W, with lambda = ${center.arrivalRate} calls per minute")
    println(String.format("%-36s %10s %12s", "abandoned waits", "L", "lambda W"))
    littlesLaw("recorded (the default)", center)
    littlesLaw("not recorded", centerServedOnly)
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 1.")
}

private fun compare(label: String, stat: ksl.utilities.statistic.StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-28s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}

private fun littlesLaw(label: String, center: CallCenter) {
    val l = center.numWaiting.acrossReplicationStatistic.average
    val lambdaW = center.arrivalRate * center.timeWaiting.acrossReplicationStatistic.average
    println(String.format("%-36s %10.4f %12.4f", label, l, lambdaW))
}
