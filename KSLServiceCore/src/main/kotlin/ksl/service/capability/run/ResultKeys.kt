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

package ksl.service.capability.run

import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationRunConfigurationJson
import ksl.service.store.ResultStore

/**
 * Derives a content key for a config document — the document *is* the
 * content-addressable request. The authoritative codec produces a deterministic
 * encoding, so identical documents yield identical keys.
 *
 * Soundness (Phase 8 plan §7): the document carries every explicit override,
 * including stream configuration, so two documents differing only in stream
 * config produce different keys. Model defaults are pinned by the model's code;
 * a rebuilt bundle that changes a model without changing its id should clear the
 * cache (a known v1 limitation, to be tied to the §6 reload).
 */
object ResultKeys {

    /**
     * [versionSalt] is a per-model code-version token (see
     * `BundleRegistry.versionSaltFor`) prepended to the hashed content, so a
     * rebuilt model — same document, new code — yields a different key and does
     * not serve a stale cached result (Phase 8 §9). Empty by default (no
     * version-aware invalidation).
     */
    fun forRunConfig(config: RunConfiguration, versionSalt: String = ""): String =
        ResultStore.sha256("$versionSalt|run:" + RunConfigurationJson.encode(config))

    fun forOptimizationConfig(config: OptimizationRunConfiguration, versionSalt: String = ""): String =
        ResultStore.sha256("$versionSalt|optimization:" + OptimizationRunConfigurationJson.encode(config))
}
