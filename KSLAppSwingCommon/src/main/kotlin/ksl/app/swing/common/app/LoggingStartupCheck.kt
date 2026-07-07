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

package ksl.app.swing.common.app

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *  Startup guardrail against a KSL desktop app shipping without a logging
 *  configuration.
 *
 *  Every KSL desktop app is expected to own a small `logback.xml` that includes
 *  `ksl-logging-base.xml` from KSLApp, which sets the root logger to WARN. If a
 *  new app module forgets that file, logback falls back to its
 *  `BasicConfigurator` default — root at DEBUG to the console — and the app
 *  silently spews the very per-stream / per-replication chatter the shared
 *  config exists to suppress.
 *
 *  This check turns that silent misconfiguration into one loud, actionable
 *  warning at launch. It relies only on slf4j (no direct logback dependency):
 *  the shared base pins the root at WARN, whereas the default fallback leaves
 *  it at DEBUG, so an enabled DEBUG level on the root logger is the tell. It is
 *  a no-op when a developer explicitly selected a config via
 *  `-Dlogback.configurationFile`.
 */
internal object LoggingStartupCheck {

    private var checked = false

    /**
     *  Emits a single WARN if the app appears to be running on logback's
     *  default configuration rather than the shared KSL base. Safe to call from
     *  any app's launch path; only the first call does anything.
     */
    fun verifyConfigured() {
        if (checked) return
        checked = true
        // A developer who explicitly selected a config knows what they want.
        if (System.getProperty("logback.configurationFile") != null) return
        // The shared base pins the root at WARN; logback's BasicConfigurator
        // default leaves it at DEBUG. Any configured KSL app has a WARN root,
        // so DEBUG-enabled on the root means no config was found.
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
        if (root.isDebugEnabled) {
            root.warn(
                "No KSL logging configuration was found on the classpath, so logback is using its " +
                    "default (DEBUG to the console). This app module is probably missing its " +
                    "src/main/resources/logback.xml — it should set the 'ksl.logfile' property and " +
                    "<include resource=\"ksl-logging-base.xml\"/>. Until then, simulation logging will " +
                    "be verbose and no ~/.ksl/logs file is written."
            )
        }
    }
}
