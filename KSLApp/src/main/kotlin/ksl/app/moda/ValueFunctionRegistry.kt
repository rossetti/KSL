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

package ksl.app.moda

import ksl.utilities.moda.LinearValueFunction
import ksl.utilities.moda.LogisticFunction
import ksl.utilities.moda.ValueFunctionIfc

/**
 *  Builds a value function from the name a document uses for it.
 *
 *  A document has to name the way scores become values in text, so something has to turn that name
 *  into the function. Keeping the mapping in one place means a document naming a function nobody
 *  supplied is caught while the study is being checked, and can be told what it could have said,
 *  instead of failing part way through a run.
 *
 *  A registry is fixed once built. Studies are meant to be reproducible, and a registry that could
 *  be changed while studies were running would mean the same document did different things
 *  depending on when it was submitted.
 */
class ValueFunctionRegistry private constructor(
    private val factories: Map<String, (Map<String, Double>) -> ValueFunctionIfc>
) {

    /** The names this registry answers to, in order. */
    val availableIds: List<String>
        get() = factories.keys.sorted()

    /** Indicates whether this registry can build a function under [id]. */
    fun contains(id: String): Boolean = factories.containsKey(id)

    /**
     *  Builds the value function named [id], set up with [parameters].
     *
     *  @throws IllegalArgumentException if nothing is registered under that name, saying what is
     */
    fun resolve(id: String, parameters: Map<String, Double> = emptyMap()): ValueFunctionIfc {
        val factory = factories[id]
        requireNotNull(factory) {
            "No value function is registered under '$id'. Available: ${availableIds.joinToString(", ")}."
        }
        return factory(parameters)
    }

    /** A registry with everything this one has, plus [id]. */
    fun with(id: String, factory: (Map<String, Double>) -> ValueFunctionIfc): ValueFunctionRegistry =
        ValueFunctionRegistry(factories + (id to factory))

    companion object {

        /** Transforms a score to a value in proportion to where it sits in the metric's range. */
        const val LINEAR: String = "linear"

        /**
         *  Transforms a score through a logistic curve, so that scores near the middle of the range
         *  separate more sharply than those at either end.
         */
        const val LOGISTIC: String = "logistic"

        /** Where the logistic curve is centred. */
        const val LOCATION: String = "location"

        /** How gradually the logistic curve turns. Must be above zero. */
        const val SCALE: String = "scale"

        /**
         *  The value functions this library supplies.
         *
         *  A logistic function needs a centre and a width; a document that names one without saying
         *  where it sits gets a curve centred at zero, which is rarely what is wanted, so validation
         *  is where a document should be told about that rather than here.
         */
        val Default: ValueFunctionRegistry = ValueFunctionRegistry(
            mapOf(
                LINEAR to { _ -> LinearValueFunction() },
                LOGISTIC to { parameters ->
                    LogisticFunction(
                        parameters[LOCATION] ?: 0.0,
                        parameters[SCALE] ?: 1.0
                    )
                }
            )
        )

        /** A registry answering to exactly what is supplied and nothing else. */
        fun of(factories: Map<String, (Map<String, Double>) -> ValueFunctionIfc>): ValueFunctionRegistry =
            ValueFunctionRegistry(factories.toMap())
    }
}
