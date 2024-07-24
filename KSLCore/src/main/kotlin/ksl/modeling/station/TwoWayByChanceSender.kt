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

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.BernoulliPicker

/**
 *  Allows a Bernoulli choice between two qObject receivers.
 *  Receives the incoming qObject and sends it two one of
 *  two receivers according to the Bernoulli picking process.
 */
class TwoWayByChanceSender(
    private val bernoulliPicker: BernoulliPicker<QObjectReceiverIfc>
) : QObjectReceiverIfc {

    override fun receive(qObject: ModelElement.QObject) {
        bernoulliPicker.randomElement.receive(qObject)
    }
}