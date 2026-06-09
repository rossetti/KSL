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

/**
 *  A pairing of a [QObjectPredicate] with the receiver to use when the predicate
 *  holds for the routed instance.
 */
data class RoutingCase(val predicate: QObjectPredicate, val receiver: QObjectReceiverIfc)

/**
 *  Routes each arriving QObject to the receiver of the first [cases] entry whose
 *  predicate holds for that instance, falling back to [default] if none match.
 *  This is the QObject-aware, N-way generalization of a two-way condition sender:
 *  the predicates are evaluated against the instance being routed.
 *
 *  @param cases the ordered list of (predicate, receiver) cases, evaluated first-match
 *  @param default the receiver used when no case matches
 */
class ConditionalRouter(
    cases: List<RoutingCase>,
    private val default: QObjectReceiverIfc
) : Router() {

    private val myCases: List<RoutingCase> = cases.toList()

    override fun selectNextReceiver(qObject: ModelElement.QObject): QObjectReceiverIfc {
        for (case in myCases) {
            if (case.predicate.test(qObject)) {
                return case.receiver
            }
        }
        return default
    }

    override fun destinations(): List<QObjectReceiverIfc> =
        myCases.map { it.receiver } + default
}
