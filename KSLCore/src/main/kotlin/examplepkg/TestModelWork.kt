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

package examplepkg

import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.random.rvariable.UniformRV

class TestModelWork {
}

fun main() {

    val m = Model()

    // it is interesting that the rv is actually usable outside the model
    val rv = RandomVariable(m, ExponentialRV())

    rv.randomSource = NormalRV()
    rv.initialRandomSource = UniformRV()

    for (i in 1..10){
        println("$i ${rv.value}")
    }
    println()

    println(m.modelElementsAsString)

    m.lengthOfReplication = 10.0
    m.lengthOfReplicationWarmUp = 5.0
    m.numberOfReplications = 3
    m.simulate()

    KSL.logger.info { "Writing to the log!" }

}