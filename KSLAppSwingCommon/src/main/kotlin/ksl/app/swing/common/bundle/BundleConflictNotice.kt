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

package ksl.app.swing.common.bundle

import ksl.app.bundle.LoadedBundle
import ksl.app.bundle.bundleSourceConflicts
import ksl.app.notification.NotificationSeverity
import ksl.app.notification.NotificationSink
import ksl.app.notification.NotificationSpec

/**
 *  If [bundles] contains the same `bundleId` from more than one source, emit a
 *  single sticky WARNING on [sink] naming the conflicting sources.  Nothing is
 *  dropped — every copy stays loaded; this only makes the overlap visible so the
 *  user can pick the source they want when selecting a model (and prune
 *  `~/.ksl/bundles/` if a copy is redundant).  A no-op when no bundle id is
 *  registered twice.
 *
 *  Call once from the host frame after discovery, e.g. at the end of `init`.
 */
fun emitBundleConflicts(sink: NotificationSink, bundles: List<LoadedBundle>) {
    val conflicts = bundleSourceConflicts(bundles)
    if (conflicts.isEmpty()) return
    val msg = buildString {
        append("Some bundles are loaded from more than one source. ")
        append("All copies stay loaded — pick the one you want when selecting a model:")
        for (c in conflicts) {
            append("\n  • ${c.displayName} (${c.bundleId}): ${c.sources.joinToString(", ")}")
        }
    }
    // dismissAfter = null keeps the notice up until the user clicks it away.
    sink.emit(NotificationSpec(message = msg, severity = NotificationSeverity.WARNING, dismissAfter = null))
}
