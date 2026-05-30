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

package ksl.app.swing.common.comparison.example

import ksl.app.comparison.InMemoryComparisonSource
import ksl.app.comparison.ResponseCategory
import ksl.app.notification.NotificationSink
import ksl.app.notification.NotificationSpec
import ksl.app.swing.common.comparison.ComparisonAnalyzerFrame
import ksl.utilities.io.KSL
import java.nio.file.Files
import javax.swing.SwingUtilities

/**
 *  Smoke-test entry point for the Comparison Analyzer.  Builds a
 *  synthetic [InMemoryComparisonSource] containing three scenarios:
 *
 *  - Two MM1-shaped scenarios with overlapping responses (NumBusy,
 *    SystemTime, NumServed) and equal replication counts so MCA
 *    works against them.
 *  - One LK Inventory scenario whose responses do not overlap the
 *    MM1 set — included so you can see the "Recorded by N of M"
 *    column behave when scenarios across different models are
 *    checked together.
 *
 *  Run from IntelliJ (right-click `main` → Run) or from the
 *  command line via Gradle's test classpath:
 *  ```
 *  ./gradlew :KSLAppSwingCommon:test --tests "*ComparisonAnalyzerSmokeMain*"
 *  ```
 *  but the typical use is the IntelliJ run gutter.
 *
 *  The output directory defaults to `KSL.outDir/comparison-analyzer-smoke`
 *  — a stable, predictable location next to the project so the
 *  generated reports are easy to find.  Override by editing the
 *  `outputDir` line below.
 */
fun main() {
    val source = buildSyntheticSource()
    val outputDir = KSL.outDir.resolve("comparison-analyzer-smoke")
    Files.createDirectories(outputDir)

    // Headless console-style sink so the smoke main streams analyzer
    // events to stdout without standing up a Swing notifications widget.
    val notifier = object : NotificationSink {
        override fun emit(spec: NotificationSpec) {
            println("[${spec.severity}] ${spec.message}")
        }
    }
    SwingUtilities.invokeLater {
        val frame = ComparisonAnalyzerFrame(
            sources = listOf(source),
            defaultOutputDir = outputDir,
            notifier = notifier
        )
        frame.isVisible = true
    }
}

private fun buildSyntheticSource(): InMemoryComparisonSource =
    InMemoryComparisonSource.builder("Smoke test · 3 synthetic scenarios").apply {
        // ── MM1 Baseline ─ NumBusy ~ 0.5, SystemTime ~ 1.5 ────────────────
        experiment("MM1 Baseline", model = "MM1") {
            response(
                "NumBusy",
                ResponseCategory.TIME_WEIGHTED,
                doubleArrayOf(0.51, 0.49, 0.52, 0.50, 0.48, 0.53, 0.51, 0.49, 0.50, 0.52)
            )
            response(
                "SystemTime",
                ResponseCategory.OBSERVATION,
                doubleArrayOf(1.45, 1.52, 1.48, 1.55, 1.49, 1.51, 1.47, 1.53, 1.50, 1.46)
            )
            response(
                "NumServed",
                ResponseCategory.COUNTER,
                doubleArrayOf(1010.0, 990.0, 1005.0, 998.0, 1012.0, 985.0, 1003.0, 994.0, 1008.0, 999.0)
            )
        }
        // ── MM1 Two Servers ─ NumBusy lower, SystemTime lower ─────────────
        experiment("MM1 Two Servers", model = "MM1") {
            response(
                "NumBusy",
                ResponseCategory.TIME_WEIGHTED,
                doubleArrayOf(0.28, 0.30, 0.27, 0.31, 0.29, 0.28, 0.30, 0.29, 0.28, 0.30)
            )
            response(
                "SystemTime",
                ResponseCategory.OBSERVATION,
                doubleArrayOf(0.85, 0.92, 0.88, 0.95, 0.89, 0.91, 0.87, 0.93, 0.90, 0.86)
            )
            response(
                "NumServed",
                ResponseCategory.COUNTER,
                doubleArrayOf(1018.0, 1002.0, 1009.0, 1015.0, 1006.0, 1011.0, 1004.0, 1013.0, 1008.0, 1007.0)
            )
        }
        // ── LK Inventory ─ disjoint response set on purpose ───────────────
        experiment("LK Inventory", model = "LKInventory") {
            response(
                "OnHandLevel",
                ResponseCategory.TIME_WEIGHTED,
                doubleArrayOf(45.0, 42.0, 48.0, 44.0, 46.0, 43.0, 47.0, 45.0)
            )
            response(
                "TotalCost",
                ResponseCategory.TIME_WEIGHTED,
                doubleArrayOf(125.5, 130.2, 122.8, 128.1, 126.7, 124.3, 129.5, 127.0)
            )
        }
    }.build()
