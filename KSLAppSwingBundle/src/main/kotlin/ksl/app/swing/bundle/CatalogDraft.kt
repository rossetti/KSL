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

package ksl.app.swing.bundle

import ksl.simulation.ModelCatalog
import ksl.simulation.ModelDescriptor
import ksl.simulation.NominatedInput
import ksl.simulation.NominatedInputKind
import ksl.simulation.NominatedOutput
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 * An editable, immutable view of a model's catalog for the Workbench's catalog
 * tab. It enumerates *every* candidate input and output from the model
 * descriptor (so the user can pick which to nominate) and carries the
 * nomination state and lean metadata per candidate.
 *
 * This is the authoring inverse of `ksl.app.swing.common.editor.CatalogLabels`
 * (which consumes a catalog); here the user builds one. [toCatalog] projects the
 * nominated rows back to a [ModelCatalog]; [from] seeds a draft from a
 * descriptor, marking rows already present in `descriptor.catalog`.
 *
 * Edits return new instances (the controller emits them on a StateFlow). List
 * order conveys priority: nominated rows are listed first (in catalog order),
 * then the remaining candidates in descriptor order.
 */
data class CatalogDraft(
    val inputs: List<InputRow>,
    val outputs: List<OutputRow>,
) {
    data class InputRow(
        val key: String,
        val kind: NominatedInputKind,
        val nominated: Boolean,
        val displayName: String? = null,
        val description: String? = null,
        val unit: String? = null,
        /** Owning/parent model element, for grouping (display-only; not in [toCatalog]). */
        val element: String? = null,
        /** Hierarchy context (e.g. element path), so auto-named RVs are identifiable. */
        val context: String? = null,
    )

    data class OutputRow(
        val name: String,
        val nominated: Boolean,
        val displayName: String? = null,
        val description: String? = null,
        val unit: String? = null,
    )

    /** Projects the nominated rows to a [ModelCatalog]; blank metadata becomes null. */
    fun toCatalog(): ModelCatalog = ModelCatalog(
        nominatedInputs = inputs.filter { it.nominated }.map {
            NominatedInput(it.key, it.kind, it.displayName.norm(), it.description.norm(), it.unit.norm())
        },
        nominatedOutputs = outputs.filter { it.nominated }.map {
            NominatedOutput(it.name, it.displayName.norm(), it.description.norm(), it.unit.norm())
        },
    )

    fun withInputNominated(key: String, nominated: Boolean): CatalogDraft =
        copy(inputs = inputs.map { if (it.key == key) it.copy(nominated = nominated) else it })

    /**
     * Sets an input's lean metadata. Supplying any non-blank label **auto-features**
     * the row: the catalog has a single ordered list, so a labelled input is by
     * definition catalogued. Clearing every field never silently un-features a row —
     * the user un-features explicitly via [withInputNominated].
     */
    fun withInputMetadata(key: String, displayName: String?, description: String?, unit: String?): CatalogDraft =
        copy(inputs = inputs.map {
            if (it.key == key) it.copy(
                displayName = displayName, description = description, unit = unit,
                nominated = it.nominated || anyMeta(displayName, description, unit),
            ) else it
        })

    fun withOutputNominated(name: String, nominated: Boolean): CatalogDraft =
        copy(outputs = outputs.map { if (it.name == name) it.copy(nominated = nominated) else it })

    /** As [withInputMetadata], for an output: any non-blank label auto-features the row. */
    fun withOutputMetadata(name: String, displayName: String?, description: String?, unit: String?): CatalogDraft =
        copy(outputs = outputs.map {
            if (it.name == name) it.copy(
                displayName = displayName, description = description, unit = unit,
                nominated = it.nominated || anyMeta(displayName, description, unit),
            ) else it
        })

    /**
     * Swaps the list positions of two inputs, so the Catalog tab's ▲▼ controls can
     * re-rank featured rows. [toCatalog] emits in list order, so a swap re-orders the
     * nominated inputs' priority in the assembled catalog.
     */
    fun swapInputs(keyA: String, keyB: String): CatalogDraft =
        copy(inputs = swap(inputs, { it.key == keyA }, { it.key == keyB }))

    /** As [swapInputs], for outputs. */
    fun swapOutputs(nameA: String, nameB: String): CatalogDraft =
        copy(outputs = swap(outputs, { it.name == nameA }, { it.name == nameB }))

    fun nominateAll(): CatalogDraft =
        copy(inputs = inputs.map { it.copy(nominated = true) }, outputs = outputs.map { it.copy(nominated = true) })

    fun clearNominations(): CatalogDraft =
        copy(inputs = inputs.map { it.copy(nominated = false) }, outputs = outputs.map { it.copy(nominated = false) })

    companion object {
        fun from(descriptor: ModelDescriptor): CatalogDraft {
            val cc = RVParameterSetter.rvParamConCatChar
            val existing = descriptor.catalog
            val nominatedInputs = existing?.nominatedInputs?.associateBy { it.key } ?: emptyMap()
            val nominatedOutputs = existing?.nominatedOutputs?.associateBy { it.name } ?: emptyMap()

            // All candidate inputs across the families, carrying each item's owning/parent
            // element and hierarchy context for grouping and labeling (esp. auto-named RVs).
            fun row(key: String, kind: NominatedInputKind, element: String?, context: String?): InputRow {
                val nom = nominatedInputs[key]
                return InputRow(key, kind, nominated = nom != null, nom?.displayName, nom?.description, nom?.unit, element, context)
            }
            val inputRows = buildList {
                descriptor.controls.numericControls.forEach { add(row(it.keyName, NominatedInputKind.NUMERIC_CONTROL, it.elementName, it.parentElementName)) }
                descriptor.controls.stringControls.forEach { add(row(it.keyName, NominatedInputKind.STRING_CONTROL, it.elementName, it.parentElementName)) }
                descriptor.controls.jsonControls.forEach { add(row(it.keyName, NominatedInputKind.JSON_CONTROL, it.elementName, it.parentElementName)) }
                descriptor.rvParameterData.forEach { rv ->
                    val element = rv.parentElementName ?: rv.rvName
                    val context = rv.elementPath.takeIf { it.isNotEmpty() }?.joinToString(" › ") ?: rv.parentElementName
                    add(row("${rv.rvName}$cc${rv.paramName}", NominatedInputKind.RV_PARAMETER, element, context))
                }
            }
            val outputRows = descriptor.responseNames.map { name ->
                val nom = nominatedOutputs[name]
                OutputRow(name, nominated = nom != null, nom?.displayName, nom?.description, nom?.unit)
            }
            return CatalogDraft(featuredFirst(inputRows, nominatedInputs.keys, InputRow::key),
                featuredFirst(outputRows, nominatedOutputs.keys, OutputRow::name))
        }

        /** Nominated entries (in their catalog order) first, then the rest in candidate order. */
        private fun <T> featuredFirst(rows: List<T>, priorityKeys: Set<String>, key: (T) -> String): List<T> {
            if (priorityKeys.isEmpty()) return rows
            val order = priorityKeys.withIndex().associate { (i, k) -> k to i }
            return rows.sortedWith(
                compareBy({ if (key(it) in order) 0 else 1 }, { order[key(it)] ?: Int.MAX_VALUE })
            )
        }
    }
}

private fun String?.norm(): String? = this?.ifBlank { null }

/** True when any supplied metadata value is non-blank (drives auto-featuring). */
private fun anyMeta(vararg values: String?): Boolean = values.any { !it.isNullOrBlank() }

/** Returns a copy of [list] with the two matched elements' positions exchanged (no-op if either is absent). */
private fun <T> swap(list: List<T>, isA: (T) -> Boolean, isB: (T) -> Boolean): List<T> {
    val ia = list.indexOfFirst(isA)
    val ib = list.indexOfFirst(isB)
    if (ia < 0 || ib < 0 || ia == ib) return list
    return list.toMutableList().apply { val t = this[ia]; this[ia] = this[ib]; this[ib] = t }
}
