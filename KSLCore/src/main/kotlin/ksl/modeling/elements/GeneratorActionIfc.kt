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
package ksl.modeling.elements

import ksl.simulation.KSLEvent

/**  This interface defines the action to occur for an EventGenerator.
 * Implementors place code in the method generate() in order to
 * provide actions that can occur when the event that was generated
 * is executed.
 *
 * @author rossetti
 */
fun interface GeneratorActionIfc {
    //TODO remove KSLEvent from the method signature
    /** The reference to the generator is available to permit control over the
     * EventGenerator within the defining code.  The event is also available.
     *
     * @param generator the generator
     */
    fun generate(generator: EventGenerator)
}