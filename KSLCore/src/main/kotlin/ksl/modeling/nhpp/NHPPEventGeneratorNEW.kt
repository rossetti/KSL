/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.modeling.nhpp

import ksl.modeling.elements.EventGenerator
import ksl.modeling.elements.GeneratorActionIfc
import ksl.modeling.elements.EventGeneratorIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.modeling.variable.RandomSourceCIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * @param parent the parent
 * @param rateFunction the rate function
 * @param generatorAction   the listener for generation
 * @param lastRate  the last rate
 * @param name the name to assign
 */
open class NHPPEventGeneratorNEW(
    parent: ModelElement,
    rateFunction: InvertibleCumulativeRateFunctionIfc,
    generatorAction: GeneratorActionIfc,
    lastRate: Double = Double.NaN,
    streamNum: Int = 0,
    name: String? = null
) : ModelElement(parent, name) {


    protected val myTBARV: NHPPTimeBtwEventRV = NHPPTimeBtwEventRV(this, rateFunction, lastRate, streamNum)

    // to be able to reuse EventGenerator it must be supplied a RVariableIfc

    private val myEventGenerator: EventGenerator = EventGenerator(this, generatorAction, myTBARV, myTBARV)

}