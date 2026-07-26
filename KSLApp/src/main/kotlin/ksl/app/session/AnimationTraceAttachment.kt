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

package ksl.app.session

import kotlinx.coroutines.CoroutineScope
import ksl.animation.AnimationCapture
import ksl.animation.AnimationLayout
import ksl.animation.AsyncAnimationSink
import ksl.animation.CaptureSpec
import ksl.animation.OverlaySpec
import ksl.simulation.Model
import java.nio.file.Path

/**
 * A [RunAttachmentIfc] that captures an animation trace for a run and writes it to a `.atf` file.
 *
 * A thin adapter over the Core [AnimationCapture] mechanism: it writes the optional authored layout
 * on attach, constructs an [AnimationCapture] over the run's model, and closes it on detach. All of
 * the capture machinery — sink composition, the per-element emitters, and the experiment/replication
 * lifecycle observer — lives in [AnimationCapture] in KSLCore; this class only binds it to the run
 * lifecycle owned by [Runner] and adds the two-file (layout + trace) convenience. The equivalent
 * Core-only entry point for callers who don't need the run-attachment plumbing is
 * `AnimationCapture.toFile`.
 *
 * @param traceFile destination `.atf` path
 * @param mode the base sink strategy ([AnimationCapture.Mode]); defaults to `MEMORY`
 * @param capturedReplications replication numbers to capture; `null` captures all. Defaults to
 *        replication 1 — the usual choice for an animation run.
 * @param description optional label written into the trace header (defaults to the model name)
 * @param asyncCapacity bounded-queue capacity used in the async mode (`AnimationCapture.Mode.ASYNC`)
 * @param layout an optional authored [AnimationLayout]; when [layoutFile] is also given, it is
 *        written on attach so the run produces the complete two-file output (`.lay.json` + `.atf`)
 * @param layoutFile destination `.lay.json` path for [layout]
 * @param captureSpec selective-capture spec (elements to include/exclude, and an optional time window)
 * @param overlays overlay-capture spec passed through to the capture
 */
class AnimationTraceAttachment(
    private val traceFile: Path,
    private val mode: AnimationCapture.Mode = AnimationCapture.Mode.MEMORY,
    private val capturedReplications: Set<Int>? = setOf(1),
    private val description: String? = null,
    private val asyncCapacity: Int = AsyncAnimationSink.DEFAULT_CAPACITY,
    private val layout: AnimationLayout? = null,
    private val layoutFile: Path? = null,
    private val captureSpec: CaptureSpec = CaptureSpec(),
    private val overlays: OverlaySpec = OverlaySpec.OFF
) : RunAttachmentIfc {

    private var capture: AnimationCapture? = null

    override fun onAttach(model: Model, scope: CoroutineScope) {
        // Write the authored layout (the static .lay.json half of the two-file format) if supplied.
        if (layout != null && layoutFile != null) {
            layout.writeToFile(layoutFile)
        }
        capture = AnimationCapture.toFile(
            model = model,
            traceFile = traceFile,
            mode = mode,
            capturedReplications = capturedReplications,
            captureSpec = captureSpec,
            overlays = overlays,
            asyncCapacity = asyncCapacity,
            description = description
        )
    }

    override fun onDetach() {
        capture?.close()
        capture = null
    }

    companion object {
        /**
         * A post-processing/replay trace: buffers each replication in memory and flushes
         * it to [traceFile]. Captures one [replication] by default.
         */
        fun replay(
            traceFile: Path,
            replication: Int = 1,
            description: String? = null,
            captureSpec: CaptureSpec = CaptureSpec(),
            overlays: OverlaySpec = OverlaySpec.OFF
        ): AnimationTraceAttachment =
            AnimationTraceAttachment(traceFile, AnimationCapture.Mode.MEMORY, setOf(replication), description, captureSpec = captureSpec, overlays = overlays)

        /**
         * A replay trace that also writes the authored [layout] to [layoutFile], producing the
         * complete two-file animation output for one [replication].
         */
        fun replay(
            traceFile: Path,
            layout: AnimationLayout,
            layoutFile: Path,
            replication: Int = 1,
            description: String? = null,
            captureSpec: CaptureSpec = CaptureSpec(),
            overlays: OverlaySpec = OverlaySpec.OFF
        ): AnimationTraceAttachment =
            AnimationTraceAttachment(traceFile, AnimationCapture.Mode.MEMORY, setOf(replication), description, layout = layout, layoutFile = layoutFile, captureSpec = captureSpec, overlays = overlays)

        /**
         * A live trace: streams events off the simulation thread to [traceFile] via a
         * background writer. Captures all replications by default.
         */
        fun live(
            traceFile: Path,
            capacity: Int = AsyncAnimationSink.DEFAULT_CAPACITY,
            description: String? = null,
            captureSpec: CaptureSpec = CaptureSpec()
        ): AnimationTraceAttachment =
            AnimationTraceAttachment(traceFile, AnimationCapture.Mode.ASYNC, null, description, capacity, captureSpec = captureSpec)
    }
}
