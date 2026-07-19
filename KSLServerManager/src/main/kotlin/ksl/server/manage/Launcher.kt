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

package ksl.server.manage

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

/**
 * The local launcher: the one thing a running server can't do about itself — start it when it is down,
 * then open the suite's built-in web console. In a hosted deployment the platform owns lifecycle and
 * this is unused; locally it is the entry point.
 *
 *   ksl-server-manager --suite <ksl-suite-mcp.jar> [--port 3001] [--no-browser]
 *
 * If the suite is already up it just opens the console; `--suite` is only needed to start a down one.
 */
fun main(args: Array<String>) {
    val port = args.valueAfter("--port")?.toIntOrNull() ?: 3001
    val healthUrl = "http://127.0.0.1:$port/health"
    val consoleUrl = "http://127.0.0.1:$port/admin"

    if (!ServerProcessInventory.isSuiteRunning(healthUrl)) {
        val jar = args.valueAfter("--suite")?.let { Path.of(it) }
        if (jar == null) {
            System.err.println("The suite is not running and no --suite <jar> was given to start it.")
            return
        }
        println("Starting the KSL suite from $jar ...")
        ServerProcessInventory.startSuite(jar, port)
        val deadline = System.nanoTime() + 20_000_000_000L // wait up to 20s for it to come up
        while (System.nanoTime() < deadline && !ServerProcessInventory.isSuiteRunning(healthUrl)) {
            Thread.sleep(250)
        }
    }

    if (ServerProcessInventory.isSuiteRunning(healthUrl)) {
        println("Suite is up. Console: $consoleUrl")
        if ("--no-browser" !in args) openBrowser(consoleUrl)
    } else {
        System.err.println("Suite did not come up on port $port; check ~/.ksl/logs.")
    }
}

private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url))
        } else {
            println("Open $url in your browser.")
        }
    }.onFailure { println("Open $url in your browser.") }
}

private fun Array<String>.valueAfter(flag: String): String? {
    val i = indexOf(flag)
    return if (i >= 0 && i + 1 < size) this[i + 1] else null
}
