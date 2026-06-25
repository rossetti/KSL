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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField

/**
 * The Identity tab: a form over the bundle's identity fields. Edits are pushed to
 * the controller (and thus the headless session) on **Apply**.
 */
class IdentityPanel(
    private val controller: BundleWorkbenchController
) : JPanel(BorderLayout()) {

    private val bundleId = JTextField(32).apply {
        toolTipText = "<html>A stable, globally-unique id you choose; a namespaced/dotted (reverse-DNS) form is " +
            "recommended, e.g. <code>edu.example.models</code>.<br>Uniqueness is your responsibility — there is no " +
            "central registry; reverse-DNS just makes collisions unlikely if you use a domain you control.<br>" +
            "Click <b>Generate</b> for a suggestion.</html>"
    }
    private val displayName = JTextField(32).apply { toolTipText = "Human-readable bundle name shown in pickers." }
    private val description = JTextField(32).apply { toolTipText = "Short description of the bundle (optional)." }
    private val version = JTextField(14).apply { toolTipText = "Your version of this bundle's content (semver encouraged)." }
    private val kslApi = JTextField(14).apply {
        isEditable = false
        toolTipText = "Informational only (not enforced anywhere): the KSL API version this bundle targets. Auto-filled."
    }
    private val generateButton = JButton("Generate").apply {
        toolTipText = "Suggest a namespaced bundle id from an organization/domain and the JAR name."
        addActionListener { generate() }
    }
    private val author = JTextField(32).apply { toolTipText = "Optional: bundle author or organisation." }
    private val homepage = JTextField(32).apply { toolTipText = "Optional: project or documentation URL." }
    private val license = JTextField(20).apply { toolTipText = "Optional: license identifier (e.g. an SPDX id like MIT)." }

    private val fields = listOf(bundleId, displayName, description, version, kslApi, author, homepage, license)

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val form = JPanel(GridBagLayout())
        var row = 0
        fun add(label: String, field: JComponent) {
            val gc = GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(4, 4, 4, 8)
            }
            form.add(JLabel(label), gc)
            form.add(field, GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(4, 0, 4, 4)
            })
            row++
        }
        val bundleIdRow = JPanel(BorderLayout(6, 0)).apply {
            add(bundleId, BorderLayout.CENTER)
            add(generateButton, BorderLayout.EAST)
        }
        add("Bundle id *:", bundleIdRow)
        add("Display name *:", displayName)
        add("Description:", description)
        add("Version *:", version)
        add("KSL API version:", kslApi)
        add("Author (optional):", author)
        add("Homepage (optional):", homepage)
        add("License (optional):", license)

        val apply = JButton("Apply bundle identity").apply { addActionListener { apply() } }
        add(JScrollPane(form), BorderLayout.CENTER)
        add(JPanel().apply { add(apply) }, BorderLayout.SOUTH)

        controller.scope.launch(Dispatchers.Swing) {
            controller.identity.collect { populate(it) }
        }
    }

    private fun populate(v: BundleWorkbenchController.IdentityView?) {
        fields.forEach { it.isEnabled = v != null }
        generateButton.isEnabled = v != null
        bundleId.text = v?.bundleId ?: ""
        displayName.text = v?.displayName ?: ""
        description.text = v?.description ?: ""
        version.text = v?.version ?: ""
        kslApi.text = v?.kslApiVersion ?: ""
        author.text = v?.author ?: ""
        homepage.text = v?.homepage ?: ""
        license.text = v?.license ?: ""
    }

    /** Suggests a namespaced bundle id from an org/domain the user supplies plus the JAR stem. */
    private fun generate() {
        val jar = controller.currentJar.value ?: return
        val stem = jar.fileName.toString().removeSuffix(".jar")
        val org = (JOptionPane.showInputDialog(
            this, "Organization / domain (reverse-DNS, e.g. edu.uark):", "Generate bundle id",
            JOptionPane.PLAIN_MESSAGE
        ) as String?)?.trim()?.ifBlank { null } ?: return
        bundleId.text = "$org.$stem".map {
            if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '-'
        }.joinToString("")
    }

    private fun apply() {
        if (!bundleId.isEnabled) return
        controller.updateIdentity {
            it.copy(
                bundleId = bundleId.text.trim(),
                displayName = displayName.text.trim(),
                description = description.text.trim(),
                version = version.text.trim().ifBlank { null },
                kslApiVersion = kslApi.text.trim().ifBlank { null },
                author = author.text.trim().ifBlank { null },
                homepage = homepage.text.trim().ifBlank { null },
                license = license.text.trim().ifBlank { null },
            )
        }
    }
}
