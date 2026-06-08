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

package ksl.modeling.agent

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 *  Property-delegate factories that enforce validity constraints on
 *  mutable agent-package defaults and instance parameters.
 *
 *  Each factory accepts an initial value (validated immediately, so a
 *  bad initial value throws at class load) and returns a delegate
 *  whose setter re-validates on every assignment. The delegate is
 *  meant for `Defaults` companion-object members and the matching
 *  instance `var`s, where invalid values would otherwise propagate
 *  silently into simulation results.
 *
 *  ```
 *  companion object Defaults {
 *      var mass: Double by positive(80.0)
 *      var infectionProb: Double by probability(0.1)
 *      var capacity: Int by positive(1)
 *  }
 *  ```
 *
 *  Error messages include the property name via `KProperty.name`, so
 *  failures point straight at the offending field.
 *
 *  Design note: introducing this small delegate set is a deliberate
 *  exception to KSL's usual inline-`require()` convention. The trade
 *  is one new file and ~150 lines of delegate plumbing in exchange
 *  for ~280 fewer lines of repetitive setter boilerplate spread
 *  across the agent package. Functions that *consume* the resulting
 *  values still apply inline `require()` at their entry points, so
 *  per-call overrides are validated independently of the property
 *  setters.
 */

private class ValidatedDouble(
    initial: Double,
    private val name: String?,
    private val predicate: (Double) -> Boolean,
    private val message: (String, Double) -> String,
) : ReadWriteProperty<Any?, Double> {
    // The KProperty name isn't available at construction, so the initial
    // check uses the optional [name] if supplied, else a generic label.
    private var value: Double = initial.also {
        require(predicate(it)) { message(name ?: "value", it) }
    }
    override fun getValue(thisRef: Any?, property: KProperty<*>): Double = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Double) {
        require(predicate(value)) { message(property.name, value) }
        this.value = value
    }
}

private class ValidatedInt(
    initial: Int,
    private val name: String?,
    private val predicate: (Int) -> Boolean,
    private val message: (String, Int) -> String,
) : ReadWriteProperty<Any?, Int> {
    private var value: Int = initial.also {
        require(predicate(it)) { message(name ?: "value", it) }
    }
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        require(predicate(value)) { message(property.name, value) }
        this.value = value
    }
}

/**
 *  Strict positivity: value must be > 0.0. Throws on construction or
 *  assignment otherwise. Pass [name] to have construction-time errors
 *  (where the property name is not yet known) name the field.
 */
fun positive(initial: Double, name: String? = null): ReadWriteProperty<Any?, Double> =
    ValidatedDouble(initial, name, { it > 0.0 }) { n, v -> "$n must be positive; was $v" }

/** Strict positivity: value must be > 0. Throws on construction or assignment otherwise. */
fun positive(initial: Int, name: String? = null): ReadWriteProperty<Any?, Int> =
    ValidatedInt(initial, name, { it > 0 }) { n, v -> "$n must be positive; was $v" }

/** Non-negativity: value must be >= 0.0. */
fun nonNegative(initial: Double, name: String? = null): ReadWriteProperty<Any?, Double> =
    ValidatedDouble(initial, name, { it >= 0.0 }) { n, v -> "$n must be non-negative; was $v" }

/** Non-negativity: value must be >= 0. */
fun nonNegative(initial: Int, name: String? = null): ReadWriteProperty<Any?, Int> =
    ValidatedInt(initial, name, { it >= 0 }) { n, v -> "$n must be non-negative; was $v" }

/** Probability: value must be in [0.0, 1.0]. */
fun probability(initial: Double, name: String? = null): ReadWriteProperty<Any?, Double> =
    ValidatedDouble(initial, name, { it in 0.0..1.0 }) { n, v -> "$n must be in [0, 1]; was $v" }

/** Strict lower bound: value must be > [minimum]. */
fun greaterThan(minimum: Double, initial: Double, name: String? = null): ReadWriteProperty<Any?, Double> =
    ValidatedDouble(initial, name, { it > minimum }) { n, v -> "$n must be > $minimum; was $v" }

/** Inclusive lower bound: value must be >= [minimum]. */
fun atLeast(minimum: Double, initial: Double, name: String? = null): ReadWriteProperty<Any?, Double> =
    ValidatedDouble(initial, name, { it >= minimum }) { n, v -> "$n must be >= $minimum; was $v" }

/** Closed range: value must be in [range]. */
fun inRange(range: ClosedRange<Double>, initial: Double, name: String? = null): ReadWriteProperty<Any?, Double> =
    ValidatedDouble(initial, name, { it in range }) { n, v -> "$n must be in $range; was $v" }
