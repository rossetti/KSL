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

import ksl.app.editor.IgnoredBundleCopy
import ksl.app.notification.NotificationSeverity
import ksl.app.notification.NotificationSink
import ksl.app.notification.NotificationSpec

/**
 *  If newest-wins dedup collapsed any redundant bundle copies at startup, emit
 *  a short, **auto-dismissing INFO** notice on [sink] pointing at
 *  *Bundles → Loaded Bundles* for detail.  Deliberately unlike the old sticky
 *  warning: one line, info-blue, gone in a few seconds — a gentle "FYI, you had
 *  duplicate jars" rather than something to dismiss.  No-op when nothing was
 *  collapsed (the common single-copy case).
 *
 *  Call once from the host frame after discovery (e.g. end of `init`), ideally
 *  via `SwingUtilities.invokeLater` so the card appears after the frame is
 *  realized and its dismiss timer starts when the user can actually see it.
 */
fun emitDedupNotice(sink: NotificationSink, ignoredCopies: List<IgnoredBundleCopy>) {
    if (ignoredCopies.isEmpty()) return
    val n = ignoredCopies.size
    val noun = if (n == 1) "copy" else "copies"
    sink.emit(
        NotificationSpec(
            message = "Collapsed $n redundant bundle $noun — see Bundles ▸ Loaded Bundles.",
            severity = NotificationSeverity.INFO
        )
    )
}
