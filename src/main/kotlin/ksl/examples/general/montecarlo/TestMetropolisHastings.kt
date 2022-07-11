/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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
    m.attach(WriteOut())
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
    override fun update(theObserved: ObservableIfc<Double>, newValue: Double?) {
        val m = theObserved as MetropolisHastings1D
        printWriter.println(m.currentX)
    }
}