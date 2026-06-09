/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.station

import ksl.simulation.ModelElement

/**
 *  A per-instance marking action applied by a [SourceStation] to each freshly
 *  created QObject. Use to sample a type id, attach a value object, set a
 *  priority, or attach a route — anything that varies per instance and can't be
 *  expressed as a static [QObjectClass].
 *
 *  The [network] parameter exposes the built network's read-only view, so a
 *  marking hook can look up `route(name)` to attach a per-instance [Route] as
 *  the QObject's sender (the DTO route-then-mark pattern). Ignore it (use
 *  `MarkingHookIfc { q, _ -> ... }`) when not needed.
 *
 *  Mirrors the source's `marking` lambda parameter; the named-hook form is what
 *  the DTO/TOML layer carries (the hook name in the spec, the implementation in
 *  the builder's hook registry).
 */
fun interface MarkingHookIfc {
    fun mark(qObject: ModelElement.QObject, network: StationNetworkCIfc)
}
