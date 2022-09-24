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
package ksl.modeling.nhpp

/** Models an invertible cumulative rate function for the non-homogeneous Poisson Process
 * @author rossetti
 */
interface InvertibleCumulativeRateFunctionIfc : CumulativeRateFunctionIfc {
    /** Returns the time associated with the supplied rate such that
     * the time is the inverse of the cumulative rate function
     *
     * @param rate the rate to evaluate
     * @return the time associated with the supplied rate
     */
    fun getInverseCumulativeRate(rate: Double): Double
}