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
package ksl.examples.general.montecarlo

import ksl.utilities.distributions.Normal
import ksl.utilities.io.KSL
import ksl.utilities.math.FunctionIfc
import ksl.utilities.observers.ObservableIfc
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.random.mcmc.MetropolisHastings1D
import ksl.utilities.random.mcmc.ProposalFunction1DIfc
import ksl.utilities.random.rvariable.NormalRV


fun main() {
    val f = Function()
    val q = PropFunction()
    val m = MetropolisHastings1D(0.0, f, q)
    m.attachObserver(WriteOut())
    m.runAll(10000)
    println(m)
}

class Function : FunctionIfc {
    var n = Normal(10.0, 1.0)
    override fun f(x: Double): Double {
        return n.pdf(x)
    }
}

class PropFunction : ProposalFunction1DIfc {
    var n = NormalRV(0.0, 0.01)
    override fun proposalRatio(current: Double, proposed: Double): Double {
        return 1.0
    }

    override fun generateProposedGivenCurrent(current: Double): Double {
        return current + n.value
    }
}

class WriteOut : ObserverIfc<Double> {
    var printWriter = KSL.createPrintWriter("MHOut.txt")
    override fun onChange(newValue: Double) {
        printWriter.println(newValue)
    }
}