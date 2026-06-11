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

package ksl.app.notification

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Authoring shape for a notification handed to `Notifications.show`.
 *
 * @property message human-readable text shown on the card.  Use a
 *   short one-liner; longer text wraps but the card is intended
 *   as a transient at-a-glance status message, not a dialog body.
 * @property severity color / icon coding per [NotificationSeverity].
 *   Defaults to [NotificationSeverity.INFO].
 * @property dismissAfter how long the card stays visible before
 *   auto-dismissal.  `null` means *manual only* — the card stays
 *   until the user clicks it.  When omitted, defaults to
 *   `defaultDismissAfter(severity)` (INFO and WARNING 5 s,
 *   ERROR 8 s per scenario workflow §4 surface 5).
 */
data class NotificationSpec(
    val message: String,
    val severity: NotificationSeverity = NotificationSeverity.INFO,
    val dismissAfter: Duration? = defaultDismissAfter(severity)
) {
    companion object {
        /** Default auto-dismiss duration per severity. */
        fun defaultDismissAfter(severity: NotificationSeverity): Duration = when (severity) {
            NotificationSeverity.INFO -> 5.seconds
            NotificationSeverity.WARNING -> 5.seconds
            NotificationSeverity.ERROR -> 8.seconds
        }
    }
}
