/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.models

import ksl.modeling.entity.ProcessModel
import ksl.simulation.ModelElement

/**
 *  This is Example 10.1 from Introduction to SIMAN V adn CINEMA V by
 *  Banks, Burnette, Kozloski, and Rose (1995) Wiley and Sons
 *
 *  There are two types of jobs that are processed within a job shop. Of the two types, 70% are
 *  type 1 and 30% are type 2. Type 1 jobs go to drilling, to milling, to grinding, and then to inspection.
 *  Type 2 jobs. Type 2 jobs go to drilling, planing, grinding, and then to inspection.
 *
 *  There are 2 drills, 3 mills, 2 grinders, and 1 inspector. The time to process each job is as follows
 *
 *  drilling: type 1 and 2, uniform(6.0, 9.0)  minutes
 *  milling: type 1, triangular(10.0, 14.0, 18.0) minutes
 *  planing: type 2, triangular(20.0, 26.0, 32.0) minutes
 *  grinding:
 *      type 1 = Discrete empirical (10%, 6 minutes), (65%, 7 minutes), (25%, 8 minutes)
 *      type 2 = Discrete empirical (10%, 6 minutes), (25%, 7 minutes), (30%, 8 minutes), (25%, 9 minutes), (10%, 10 minutes)
 *  inspection: type 1, normal (mean = 3.6 minutes, std dev = 0.6)
 *  90% of jobs pass inspection, 5% go to rework, and 5% are scrapped
 *
 *  The queues at drilling, milling, planing, grinding, and inspections have capacities: 2, 3, 3, 2, and 4, respectively.
 *  Any jobs that attempt to enter a queue that has reached its capacity are immediately sent to an overflow
 *  area and leave the system.
 *  
 */
class ConveyorExample(parent: ModelElement, name: String? = null): ProcessModel(parent, name) {
}