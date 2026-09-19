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
package ksl.modeling.guidedpath

/**
 * What a closure should do when some zone it wants is already promised to another closure.
 *
 * An **overlap** is narrower than it first sounds, and the distinction is the one this whole
 * mechanism turns on. A zone another holder already *holds* is not an overlap: asking for it
 * succeeds and waits for the hold to end, under every value here. An overlap is a zone that has
 * been promised to a closure which has **not yet been granted it** -- a region still draining.
 *
 * There is no right answer, which is why this is a parameter rather than a policy the guide path
 * keeps. Two spills in the same aisle are usually *one* spill, cleaned once; two maintenance
 * windows on the same leg are usually two, done in turn. Only the model knows which.
 */
enum class ZoneOverlap {

    /**
     * Refuse, loudly. The request raises, and the message says which zone is promised to whom.
     *
     * The default, and deliberately the noisiest option: a model whose closures can land on the
     * same zone -- spills at random locations, most obviously -- has to say what that means, and
     * being made to say it beats any answer the library could pick on the modeller's behalf.
     */
    RAISE,

    /**
     * Answer null, and reserve nothing.
     *
     * The same judgement as [RAISE] -- the model decides -- with the decision taken in code rather
     * than by a raise. `tryRequestZones`, `tryHoldZonesFor` and `trySeizeZones` are this value's
     * spelling, and the guide path is left exactly as it was found.
     */
    REFUSE,

    /**
     * Take a place in the queue behind whoever is already promised the zone.
     *
     * The closure is accepted, waits its turn, and is granted when every zone of its set is both
     * empty and promised to it rather than to somebody ahead of it. Order per zone is the order the
     * requests were made.
     *
     * **Ask for this only when serialising the closures is what the model means.** It is not a
     * convenience for making an inconvenient refusal go away: two spills in the same aisle queued
     * this way are cleaned *twice, in series*, which for one spill reported twice is simply wrong
     * and is wrong quietly. Where it is right -- maintenance windows on one leg, a crossing served
     * in turns, work that genuinely repeats -- it saves the model writing the waiting itself.
     */
    QUEUE
}
