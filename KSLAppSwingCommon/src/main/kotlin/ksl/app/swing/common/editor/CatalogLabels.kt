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

package ksl.app.swing.common.editor

import ksl.simulation.ModelCatalog
import ksl.simulation.NominatedInput
import ksl.simulation.NominatedOutput

/**
 *  Shared helpers for surfacing author-curated catalog metadata
 *  (`ksl.simulation.ModelCatalog`) in the editor widgets: lookups keyed by the
 *  canonical input key / output name, and HTML tooltip builders that render a
 *  nominated item's display name, description, and unit.
 *
 *  This is the **labelling** layer (Phase A of catalog → app integration): it
 *  enriches existing surfaces without changing their order or contents.  Every
 *  helper degrades to an empty map / `null` tooltip when no catalog is present,
 *  so callers behave exactly as before for non-cataloged models.
 */
internal object CatalogLabels {

    /** Nominated inputs keyed by their canonical key (control keyName or "rvName.paramName"). */
    fun inputsByKey(catalog: ModelCatalog?): Map<String, NominatedInput> =
        catalog?.nominatedInputs?.associateBy { it.key } ?: emptyMap()

    /** Nominated outputs keyed by response/counter name. */
    fun outputsByName(catalog: ModelCatalog?): Map<String, NominatedOutput> =
        catalog?.nominatedOutputs?.associateBy { it.name } ?: emptyMap()

    /** An HTML tooltip for a nominated input, or `null` when the item is not nominated. */
    fun tooltip(input: NominatedInput?): String? =
        if (input == null) null else buildTooltip(input.displayName, input.description, input.unit)

    /** An HTML tooltip for a nominated output, or `null` when the item is not nominated. */
    fun tooltip(output: NominatedOutput?): String? =
        if (output == null) null else buildTooltip(output.displayName, output.description, output.unit)

    private fun buildTooltip(displayName: String?, description: String?, unit: String?): String? {
        val parts = mutableListOf<String>()
        displayName?.takeIf { it.isNotBlank() }?.let { parts += "<b>${escape(it)}</b>" }
        description?.takeIf { it.isNotBlank() }?.let { parts += escape(it) }
        unit?.takeIf { it.isNotBlank() }?.let { parts += "Unit: ${escape(it)}" }
        return if (parts.isEmpty()) null else "<html>" + parts.joinToString("<br>") + "</html>"
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
