/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.station

import ksl.modeling.variable.RandomVariable
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.KSLRandom

class TwoWayByChanceSender(
    parent: ModelElement,
    firstProbability: Double,
    private val firstReceiver: ReceiveQObjectIfc,
    private val secondReceiver: ReceiveQObjectIfc,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : Station(parent, null, name = name) {

    init {
        require(!(firstProbability <= 0.0 || firstProbability >= 1.0)) { "Probability must be (0,1)" }
    }

    private var myChoiceRV = RandomVariable(
        this, BernoulliRV(firstProbability, stream),
        "${this.name}:ChoiceRV"
    )

    var firstProbability: Double = firstProbability
        set(value) {
            require(!(value <= 0.0 || value >= 1.0)) { "Probability must be (0,1)" }
            field = value
            val stream = myChoiceRV.initialRandomSource.rnStream
            myChoiceRV.initialRandomSource = BernoulliRV(value, stream)
        }

    override fun receive(qObject: QObject) {
        if (myChoiceRV.value == 1.0) {
            firstReceiver.receive(qObject)
        } else {
            secondReceiver.receive(qObject)
        }
    }
}