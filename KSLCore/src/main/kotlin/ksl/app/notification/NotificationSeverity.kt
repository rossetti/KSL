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

/**
 * Severity bucket used by the notification surface.  Independent from the
 * `ValidationSeverity` used by the validation framework — toast
 * notifications announce *events*, not validation state (per
 * scenario workflow §4 surface 5).
 *
 *  - [INFO]    — neutral / success events (file saved, run
 *    submitted).
 *  - [WARNING] — non-fatal issues (bundle load failure, skipped
 *    scenarios on submit).
 *  - [ERROR]   — outright failures (save failed, run crashed).
 */
enum class NotificationSeverity { INFO, WARNING, ERROR }
