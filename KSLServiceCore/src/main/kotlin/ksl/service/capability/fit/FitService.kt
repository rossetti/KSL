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

package ksl.service.capability.fit

import ksl.app.dist.DistributionModelingSession
import ksl.app.dist.config.FitSpec
import ksl.app.dist.session.FitEvent
import ksl.app.dist.session.FitResult
import ksl.service.job.JobHandleView
import ksl.service.job.asJobView

/**
 * The service-core surface for capability B — fitting distributions to a
 * dataset (strategic plan §5.6).
 *
 * It is the lightest of the three capabilities precisely because the symmetry
 * the reassessment (§2.3) uncovered does the heavy lifting:
 * `DistributionModelingSession` is a structural twin of `KSLAppSession`, so
 * [submit] is one line — submit the [FitSpec] and adapt the returned
 * `FitHandle` to the capability-agnostic [JobHandleView] via [asJobView]. From
 * there a fit flows through the very same `JobManager`, event journal, and
 * transport layer as a model run.
 *
 * And unlike the run capability, the *results need no projection*: `ksl.app.dist`
 * already ships `@Serializable` result DTOs (`FitResult` → `FitResultData`), so
 * the terminal value is wire-ready as delivered.
 *
 * @param session the headless fitting session; owned and closed by this service
 */
class FitService(
    private val session: DistributionModelingSession = DistributionModelingSession(),
) : AutoCloseable {

    /**
     * Submits a fitting job and returns it as a generic [JobHandleView] ready to
     * register with a `JobManager<FitEvent, FitResult>`.
     *
     * @param spec a single fit ([FitSpec.Single]) or a batch ([FitSpec.Batch])
     * @param validate when true, the configuration is validated before running
     */
    fun submit(spec: FitSpec, validate: Boolean = true): JobHandleView<FitEvent, FitResult> =
        session.submit(spec, validate).asJobView()

    override fun close() = session.close()
}
