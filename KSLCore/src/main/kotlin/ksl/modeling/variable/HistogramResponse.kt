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

package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import ksl.utilities.IdentityIfc
import ksl.utilities.statistic.CachedHistogram
import ksl.utilities.statistic.HistogramIfc

interface HistogramResponseCIfc : IdentityIfc {
    val histogram: HistogramIfc
    val response: ResponseCIfc
}

/**
 * Tabulates a histogram for the indicated response.
 *
 * The [cacheSize] represents the amount of data used to configure the break points
 * of the histogram [CachedHistogram][ksl.utilities.statistic.CachedHistogram].
 *
 * If the amount of data observed is less than cache size and greater
 * than or equal to 2, the returned histogram will be configured on whatever data
 * was available in the cache. Thus, bin settings may change as more
 * data is collected until the cache is full. Once the cache is full the returned histogram
 * is permanently configured based on all data in the cache.
 * The default cache size is 512 observations.
 *
 * The histogram tabulates all within replication observations regardless of replication.
 * That is, the histogram is based on every observation for every replication.  It observes
 * observations that may have been within a warmup period even if the modeler specifies
 * a warmup period.
 *
 * @param theResponse the response variable to form a histogram on
 * @param cacheSize the minimum amount of data needed to configure the break points
 * @param name the name of the model element
 */
class HistogramResponse(
    theResponse: Response,
    val cacheSize: Int = 512,
    name: String? = "${theResponse.name}:Histogram",
) : ModelElement(theResponse, name), HistogramResponseCIfc {

    internal val myResponse = theResponse
    override val response: ResponseCIfc
        get() = myResponse

    private val myObserver = ResponseObserver()

    init {
        myResponse.attachModelElementObserver(myObserver)
    }

    private val myHistogram: CachedHistogram = CachedHistogram(cacheSize, name = this.name)
    override val histogram: HistogramIfc
        get() = myHistogram

    override fun beforeExperiment() {
        myHistogram.reset()
    }

    /**
     *  Causes the histogram response to stop observing the underlying
     *  response.
     */
    fun stopCollecting(){
        myResponse.detachModelElementObserver(myObserver)
    }

    /**
     *  Causes the histogram response to start observing the underlying response
     */
    fun startCollecting(){
        if (!myResponse.isModelElementObserverAttached(myObserver)){
            myResponse.attachModelElementObserver(myObserver)
        }
    }

    private inner class ResponseObserver : ModelElementObserver() {
        override fun update(modelElement: ModelElement) {
            // must be a response because only attached to responses
            val response = modelElement as Response
            myHistogram.collect(response.value)
        }
    }

    override fun afterExperiment() {
//        println(histogram)
    }
}