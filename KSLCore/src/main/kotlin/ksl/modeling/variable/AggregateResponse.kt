/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

/**
 * Aggregate (per-observation) response that echoes every observation
 * made on any chained source onto itself. The aggregate's within- and
 * across-replication statistics are then a pooled view of every
 * observation across every source: a source that fires twice as often
 * contributes twice as many observations to the pool.
 *
 * Parallel to [AggregateTWResponse] for non-time-weighted (per-event)
 * responses. Use this when you want to compute a single mean (or any
 * other Welford statistic) over the combined stream of observations
 * from a set of [Response] sources — for example, an average fill-rate
 * over a collection of inventories where each inventory pushes a
 * 1.0/0.0 observation on every customer demand it sees.
 *
 * **Echo semantics.** Each time a chained source's [Response.value]
 * is assigned, this aggregate's `value` is assigned the same number.
 * That assignment runs through the standard [Response.assignValue]
 * path, so the aggregate's own within-replication Welford
 * accumulator records the observation, and the aggregate's own
 * observers (if any) fire downstream — exactly like
 * [AggregateTWResponse].
 *
 * **Weighting.** Because every source observation produces exactly
 * one aggregate observation, sources are weighted by their
 * observation count. If two sources should be weighted equally
 * regardless of how often they fire, the caller should aggregate
 * source means externally rather than chain through this class.
 *
 * @param parent the parent model element
 * @param name an optional name for the aggregate
 */
class AggregateResponse @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
) : Response(parent, name) {

    private val responses = mutableSetOf<Response>()
    private val myObserver = ResponseObserver()

    private inner class ResponseObserver : ModelElementObserver() {
        override fun update(modelElement: ModelElement) {
            // must be a Response because only attached to Response
            val source = modelElement as Response
            this@AggregateResponse.value = source.value
        }
    }

    /**
     * Attach [response] as a source. The aggregate will echo every
     * subsequent observation on [response] onto itself.
     */
    fun observe(response: Response) {
        if (!responses.contains(response)) {
            responses.add(response)
            response.attachModelElementObserver(myObserver)
        }
    }

    /**
     * Read-only-view variant of [observe]. Narrows to [Response] at
     * runtime; if [response] is not the concrete type, this is a no-op.
     */
    fun observe(response: ResponseCIfc) {
        if (response is Response) observe(response)
    }

    /**
     * Attach every entry of [responses] as a source. Accepts the
     * read-only [ResponseCIfc] view because `Collection<out E>` is
     * covariant — callers may pass `Collection<Response>` directly.
     */
    @Suppress("unused")
    fun observeAll(responses: Collection<ResponseCIfc>) {
        for (r in responses) observe(r)
    }

    /** Detach [response] as a source. Safe if never attached. */
    fun remove(response: Response) {
        if (responses.contains(response)) {
            responses.remove(response)
            response.detachModelElementObserver(myObserver)
        }
    }

    /** Read-only-view variant of [remove]. */
    fun remove(response: ResponseCIfc) {
        if (response is Response) remove(response)
    }

    /** Detach every entry of [responses] as a source. */
    @Suppress("unused")
    fun removeAll(responses: Collection<ResponseCIfc>) {
        for (r in responses) remove(r)
    }
}
