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

package ksl.app.config

import kotlinx.serialization.Serializable

/**
 * Whether the scenarios in a `ksl.app.config.RunConfiguration` run
 * one at a time or in parallel.  Per scenario workflow §10:
 *
 *  - [SEQUENTIAL] (default for new documents) — scenarios run one
 *    at a time in the user-authored order.  Predictable progress
 *    reporting; no inter-scenario contention.  Less surprising for
 *    new users.
 *  - [CONCURRENT] — scenarios run in parallel via the substrate's
 *    `ConcurrentScenarioRunner`.  Parallelism is sized so each
 *    scenario has at least one core.
 *
 * The mode is a property of the document so opening a saved
 * `RunConfiguration` runs it the same way it last ran.
 *
 * **Substrate-prep only.**  The field is recorded on
 * [RunConfiguration.executionMode] and round-trips through both
 * codecs; `ScenarioOrchestrator` does not yet consume it.  Honoring
 * the mode at submit time lands in a separate commit alongside the
 * runner-level sequential mode.
 */
@Serializable
enum class ExecutionMode { SEQUENTIAL, CONCURRENT }
