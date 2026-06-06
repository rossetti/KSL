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

package ksl.app.swing.results.panel

import ksl.app.config.ReportFormat
import ksl.app.notification.NotificationSink
import ksl.app.swing.common.comparison.ComparisonAnalyzerTabPanel
import ksl.app.swing.results.ResultsAppController
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 *  Cross-experiment comparison tab — the flagship MCB / box-plot /
 *  confidence-interval showcase.
 *
 *  This is a thin wrapper around the shared
 *  [ComparisonAnalyzerTabPanel] from `KSLAppSwingCommon`: the panel
 *  owns the experiments-and-analyses UI and the per-analysis dialogs,
 *  and the host merely feeds it the database's comparison source.  On
 *  every database change the wrapper calls
 *  [ComparisonAnalyzerTabPanel.setSources] with the controller's
 *  current [ksl.app.comparison.KSLDatabaseComparisonSource] (or an
 *  empty list, which shows the empty-state card).
 *
 *  Per-analysis output directory and formats come from the controller
 *  via the provider lambdas, so a Configure… dialog always reflects
 *  the host's current output location.
 */
class CompareExperimentsPanel(
    private val controller: ResultsAppController,
    notifier: NotificationSink
) : JPanel(BorderLayout()) {

    private val analyzerPanel = ComparisonAnalyzerTabPanel(
        defaultOutputDirProvider = { controller.outputDir },
        defaultFormatsProvider = { setOf(ReportFormat.HTML) },
        notifier = notifier
    )

    init {
        analyzerPanel.setEmptyStateText("Open a database to compare experiments.")
        analyzerPanel.setSources(emptyList())
        add(analyzerPanel, BorderLayout.CENTER)
        controller.addListener { reload() }
    }

    private fun reload() {
        val source = controller.comparisonSource
        analyzerPanel.setSources(if (source == null) emptyList() else listOf(source))
    }
}
