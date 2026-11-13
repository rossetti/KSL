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
 *  and at the end of each replication, it checks that a resource's units are accounted for. It is not a
 *  general auditor.
 *
 *  It checks today that the units busy equal the units allocated and do not exceed the capacity, except
 *  while a capacity decrease waits for busy units to be released. As units
 *  can be lent and delays frozen, it gains the checks for those: that the units busy equal the units
 *  allocated less those lent, that no loan exceeds its lender's missing units, and that each entity's
 *  freeze count matches the reasons it should have with no event on the calendar for a frozen delay.
 */
internal object LoansAndFreezesCheck {

    /** Checks [resource]; the message names the resource and what is out of balance. */
    fun check(resource: Resource) {
        val allocated = resource.allocations().sumOf { it.amount }
        require(resource.numBusy == allocated) {
            "${resource.name}: ${resource.numBusy} units busy, but ${allocated} are allocated"
        }
        // above the capacity only while a capacity decrease waits for busy units to be released
        require(resource.numBusy <= resource.capacity || resource.isPendingCapacityChange) {
            "${resource.name}: ${resource.numBusy} units busy exceeds the capacity ${resource.capacity}"
        }
    }

    /** Checks each of [resources]. */
    fun check(vararg resources: Resource) {
        for (r in resources) check(r)
    }
}
