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
 * A holder that lets a population onto the space it is holding.
 *
 * The one exception to "a zone is held by at most one thing, and a held zone admits nobody", and it
 * exists because a pedestrian crossing needs a state the rest of the subsystem has no way to say:
 * **closed to vehicles, open to people.**
 *
 * Every other way of shutting a zone shuts it to everyone. A zone that is held refuses an admission
 * because it is not free; a zone that is reserved refuses one because the reservation comes first;
 * a zone whose population is non-empty refuses *vehicles*, but only once somebody is already
 * standing in it -- which is too late to be how a crossing keeps traffic off while its people
 * gather. So a crossing takes its zones as a holder, which drains vehicles off them through the
 * ordinary all-or-nothing machinery, and then admits its own population onto what it holds.
 *
 * That is the whole of what this interface says. It carries no members: it is a statement about who
 * may be passed to the internal admission path as the holder to admit *under*, and it is a type
 * rather than a boolean so that the invariant harness can tell a legitimate crossing from the
 * defect it would otherwise report -- a zone both held and populated is a bug everywhere else, and
 * a checker that could not tell the difference would have to stop checking it at all.
 *
 * Implementing this is a promise about lifetime rather than about behaviour: **whatever is admitted
 * under a hold must be gone before the hold is given back.** A crossing that released its zones
 * with people still on them would leave a population behind that no vehicle can pass and nothing
 * will ever remove, which is the same permanent stall that a closure never granted produces, minus
 * the report.
 */
interface ZonePopulationHostIfc : ZoneHolderIfc
