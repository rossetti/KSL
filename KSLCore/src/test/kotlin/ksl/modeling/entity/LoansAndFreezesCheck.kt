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

package ksl.modeling.entity

/**
 *  A test helper for interrupted work: called at chosen points in the tests of failures and preemption,
 *  and at the end of each replication, it checks that a resource's units are accounted for. The checks are
 *  the library's own (`ResourceInvariants`), which `-Dksl.checkInvariants=true` also runs after every event.
 *
 *  It checks that the units busy equal the units allocated less those lent to a borrower, and do not
 *  exceed the capacity except while a capacity decrease waits for busy units to be released; that each
 *  allocation's units missing by loan equal its loans out and never exceed its amount; that no loan
 *  outlives its borrower's allocation; that every wait on an allocation's triggers is still open; and that
 *  an entity in a delay that reports an interruption is never held by a missing unit.
 */
internal object LoansAndFreezesCheck {

    /** Checks [resource] with the library's own invariant checks; the message names what is out of balance. */
    fun check(resource: Resource) {
        ResourceInvariants.check(resource)
    }

    /** Checks that an entity in a delay that reports an interruption is never held by a missing unit. */
    fun checkEntity(entity: ProcessModel.Entity) {
        if (entity.isInReportingDelay) {
            require(entity.myFrozenBy.isEmpty()) {
                "entity ${entity.id} is in a delay that reports, yet held by ${entity.myFrozenBy.size} allocations"
            }
        }
    }

    /** Checks each of [resources]. */
    fun check(vararg resources: Resource) {
        for (r in resources) check(r)
    }
}
