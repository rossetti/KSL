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

package ksl.app.dist.config

/**
 * Whether a fitting job models a continuous distribution (real-valued data,
 * PDF-based scoring) or a discrete one (integer-valued data, PMF-based GoF).
 *
 * The two paths intentionally differ in scoring and ranking semantics: the
 * continuous side uses the MODA-weighted scoring built into PDFModeler; the
 * discrete side ranks by chi-squared p-value. This enum keeps that fork
 * explicit in the configuration rather than letting it sneak in through
 * defaults.
 */
enum class DistributionKind { CONTINUOUS, DISCRETE }
