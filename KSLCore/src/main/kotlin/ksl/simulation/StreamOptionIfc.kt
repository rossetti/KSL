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
package ksl.simulation

/**
 *
 * @author rossetti
 */
interface StreamOptionIfc {

    /**
     * Sets/Gets the current reset next sub-stream option true means, that it is set
     * to jump to the next sub-stream after each replication
     *
     */
    var resetNextSubStreamOption: Boolean

    /**
     * Sets/Gets the reset start stream option, true means that it will be reset to
     * the starting stream
     *
     */
    var resetStartStreamOption: Boolean
}