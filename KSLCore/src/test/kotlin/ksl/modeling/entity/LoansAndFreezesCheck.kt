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
 *  It checks that the units busy equal the units allocated less those lent to a borrower, and do not
 *  exceed the capacity except while a capacity decrease waits for busy units to be released; that each
 *  allocation's units missing by loan equal its loans out and never exceed its amount; that no loan
 *  outlives its borrower's allocation; and that an entity in a delay that reports an interruption is never
 *  held by a missing unit.
 */
internal object LoansAndFreezesCheck {

    /** Checks [resource]; the message names the resource and what is out of balance. */
    fun check(resource: Resource) {
        val allocated = resource.allocations().sumOf { it.amount }
        // a unit lent counts once, busy for its borrower
        val lent = resource.loans.filter { it.lender != null }.sumOf { it.amount }
        require(resource.numBusy == allocated - lent) {
            "${resource.name}: ${resource.numBusy} units busy, but ${allocated} are allocated and $lent lent"
        }
        for (a in resource.allocations()) {
            val out = resource.loans.filter { it.lender === a }.sumOf { it.amount }
            require(a.missingByLoan == out) {
                "${resource.name}: allocation ${a.id} has ${a.missingByLoan} units missing by loan, but $out are lent"
            }
            require(a.missingUnits <= a.amount) {
                "${resource.name}: allocation ${a.id} has ${a.missingUnits} units missing of ${a.amount}"
            }
        }
        for (loan in resource.loans) {
            require(loan.borrower.isAllocated) { "${resource.name}: a loan to the released allocation ${loan.borrower.id} is still recorded" }
        }
        // above the capacity only while a capacity decrease waits for busy units to be released
        require(resource.numBusy <= resource.capacity || resource.isPendingCapacityChange) {
            "${resource.name}: ${resource.numBusy} units busy exceeds the capacity ${resource.capacity}"
        }
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
