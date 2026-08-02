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

package ksl.service.capability.moda

import ksl.app.moda.ModaDocument
import ksl.app.moda.ModaDocumentValidator
import ksl.app.moda.ModaEvent
import ksl.app.moda.ModaSession
import ksl.app.moda.ModaSourceResolver
import ksl.app.moda.ModaStudyOutcome
import ksl.app.moda.ValidationIssue
import ksl.app.moda.ValueFunctionRegistry
import ksl.service.job.JobHandleView
import ksl.service.job.asJobView

/**
 * Offers multi-objective decision studies as a capability of the server.
 *
 * Kept as thin as the fitting service it mirrors. Everything a submitted study
 * needs beyond running — a limit on how many run at once, a journal of what
 * happened, status, cancellation, retention and expiry — comes from the shared
 * job spine rather than from anything written here, so a decision study behaves
 * like a model run or a distribution fit in all the ways a caller can observe.
 *
 * Each study gets its own model, built inside its own run and never handed out
 * except as a recorded result, so studies running at the same time cannot
 * disturb one another.
 */
class ModaService(
    private val session: ModaSession = ModaSession(),
) : AutoCloseable {

    /**
     * Submits [document] to be run, optionally reading its scores from
     * [sourceResolver] rather than from wherever the session would look.
     */
    fun submit(
        document: ModaDocument,
        sourceResolver: ModaSourceResolver? = null
    ): JobHandleView<ModaEvent, ModaStudyOutcome> =
        session.submit(document, sourceResolver).asJobView()

    /**
     * Checks [document] without running it, for a caller that wants to know
     * whether a study is sound before committing to it.
     *
     * Answered directly rather than as a job, because checking is quick and
     * makes nothing that needs keeping, so putting it through the queue would
     * cost a caller more than it does to answer.
     */
    fun check(
        document: ModaDocument,
        registry: ValueFunctionRegistry = ValueFunctionRegistry.Default,
        sourceResolver: ModaSourceResolver = ModaSourceResolver()
    ): List<ValidationIssue> = ModaDocumentValidator(registry, sourceResolver).validate(document)

    override fun close() = session.close()
}
