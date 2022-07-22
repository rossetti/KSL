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
package ksl.utilities.random.rvariable

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.statistic.Statistic

/**
 * Allows a constant value to pretend to be a random variable
 */
open class ConstantRV(var constVal: Double) : RVariableIfc, IdentityIfc by Identity() {

    lateinit var statistic: Statistic

    final override var collectStatistics: Boolean = false
        set(value) {
            field = value
            if (field) {
                if (!::statistic.isInitialized) {
                    statistic = Statistic(name + "_Stats")
                }
            }
        }

    override fun statistics(): Statistic {
        if (!collectStatistics){
            collectStatistics = true
        }
        return statistic.instance()
    }

    override fun resetStatistics() {
        if (::statistic.isInitialized) {
            statistic.reset()
        }
    }

    override fun previous(): Double = constVal

    override fun instance(stream: RNStreamIfc): ConstantRV {
        return ConstantRV(constVal)
    }

    override fun instance(): ConstantRV {
        return ConstantRV(constVal)
    }

    override fun toString(): String {
        return "ConstantRV(value=$constVal)"
    }

    override fun sample(): Double {
        if (collectStatistics){
            statistic.value = constVal
        }
        return constVal
    }

    override fun resetStartStream() {}
    override fun resetStartSubStream() {}
    override fun advanceToNextSubStream() {}
    override var antithetic: Boolean
        get() = false
        set(flag) {}

    override fun antitheticInstance(): RVariableIfc {
        return ConstantRV(constVal)
    }

    override var rnStream: RNStreamIfc
        get() = KSLRandom.defaultRNStream()
        set(stream) {}

    companion object {
        /**
         * A constant to represent zero for sharing
         */
        val ZERO = ConstantRV(0.0)

        /**
         * A constant to represent one for sharing
         */
        val ONE = ConstantRV(1.0)

        /**
         * A constant to represent two for sharing
         */
        val TWO = ConstantRV(2.0)

        /**
         * A constant to represent positive infinity for sharing
         */
        val POSITIVE_INFINITY = ConstantRV(Double.POSITIVE_INFINITY)

    }


}