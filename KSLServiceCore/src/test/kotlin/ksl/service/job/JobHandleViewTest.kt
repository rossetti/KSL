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

package ksl.service.job

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import ksl.app.dist.session.FitEvent
import ksl.app.dist.session.FitHandle
import ksl.app.dist.session.FitResult
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Proves the capability-agnostic spine: a model-run handle and a
 * distribution-fitting handle — from two independent KSLCore sessions — both
 * adapt to the same [JobHandleView]. This is the structural guarantee that the
 * server does not lock out `ksl.app.dist`; fitting is a peer of running, not an
 * afterthought.
 */
class JobHandleViewTest {

    private class FakeRunHandle(
        override val runId: String,
        override val events: SharedFlow<RunEvent>,
        override val result: Deferred<RunResult>,
    ) : RunHandle {
        var cancelledWith: String? = null
        override fun cancel(reason: String) { cancelledWith = reason }
    }

    private class FakeFitHandle(
        override val fitId: String,
        override val events: SharedFlow<FitEvent>,
        override val result: Deferred<FitResult>,
    ) : FitHandle {
        var cancelledWith: String? = null
        override fun cancel(reason: String) { cancelledWith = reason }
    }

    @Test
    fun `run and fit handles both present as JobHandleView`() {
        val runHandle = FakeRunHandle(
            runId = "run-1",
            events = MutableSharedFlow(),
            result = CompletableDeferred(),
        )
        val fitHandle = FakeFitHandle(
            fitId = "fit-1",
            events = MutableSharedFlow(),
            result = CompletableDeferred(),
        )

        // Both adapt to the same generic type — different capabilities, one spine.
        val views: List<JobHandleView<*, *>> = listOf(runHandle.asJobView(), fitHandle.asJobView())
        assertEquals(listOf("run-1", "fit-1"), views.map { it.jobId })

        // Delegation is transparent: the view exposes the source's own flows.
        assertSame(runHandle.events, runHandle.asJobView().events)
        assertSame(fitHandle.result, fitHandle.asJobView().result)

        // Cancellation routes through to the underlying capability handle.
        runHandle.asJobView().cancel("stop run")
        fitHandle.asJobView().cancel("stop fit")
        assertEquals("stop run", runHandle.cancelledWith)
        assertEquals("stop fit", fitHandle.cancelledWith)
    }
}
