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

package ksl.examples.release

import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import java.io.File
import kotlin.reflect.full.primaryConstructor

/**
 *  Runs one example model for the pinned-results test, in a JVM of its own, and writes every response and
 *  counter at full precision. A model from a builder runs with the builder's settings; a model built from
 *  its class gets only its parent and runs 5 replications of 2,000 time units.
 *
 *  Arguments: `builder` or `class`, the class name, and the file to write.
 */
fun main(args: Array<String>) {
    val (kind, className, outPath) = args
    val model: Model = if (kind == "builder") {
        (Class.forName(className).getDeclaredConstructor().newInstance() as ModelBuilderIfc).build(null, null)
    } else {
        val m = Model("Pinned", autoCSVReports = false)
        val ctor = Class.forName(className).kotlin.primaryConstructor ?: error("$className has no primary constructor")
        ctor.callBy(mapOf(ctor.parameters.first() to (m as ModelElement)))
        m.numberOfReplications = 5
        m.lengthOfReplication = 2_000.0
        m
    }
    val out = StringBuilder()
    out.append("reps=${model.numberOfReplications} length=${model.lengthOfReplication} warmup=${model.lengthOfReplicationWarmUp}\n")
    model.simulate()
    for (r in model.responses.sortedBy { it.name }) {
        val s = r.acrossReplicationStatistic
        out.append("R ${r.name} n=${s.count} avg=${s.average} sd=${s.standardDeviation} min=${s.min} max=${s.max}\n")
    }
    for (c in model.counters.sortedBy { it.name }) {
        val s = c.acrossReplicationStatistic
        out.append("C ${c.name} n=${s.count} avg=${s.average} sd=${s.standardDeviation}\n")
    }
    File(outPath).writeText(out.toString())
}
