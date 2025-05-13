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
package ksl.modeling.elements

/**  This interface defines the action to occur for an EventGenerator.
 * Implementors place code in the method generate() in order to
 * provide actions that can occur when the event that was generated
 * is executed.
 *
 * @author rossetti
 */
fun interface GeneratorActionIfc {

    /** The reference to the generator is available to permit control over the
     * EventGenerator within the defining code.
     *
     * @param generator the generator
     */
    fun generate(generator: BaseGeneratorIfc)

}

fun interface EndGeneratorActionIfc {

    /** The reference to the generator is available to permit control over the
     * EventGenerator within the defining code.
     *
     * @param generator the generator
     */
    fun endGeneration(generator: BaseGeneratorIfc)
}