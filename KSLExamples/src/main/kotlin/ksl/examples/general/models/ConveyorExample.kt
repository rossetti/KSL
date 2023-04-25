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
 *  Type 2 jobs go to drilling, planing, grinding, and then to inspection. The job arrival process is
 *  a Poisson process with a mean time between arrivals of 5 minutes.
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
 *
 *  90% of jobs pass inspection, 5% go to rework, and 5% are scrapped. The rework jobs are sent back to drilling and
 *  start their processing again. At the grinder, type 1 jobs have priority over type 2 jobs.
 *
 *  The queues at drilling, milling, planing, grinding, and inspections have capacities: 2, 3, 3, 2, and 4, respectively.
 *  Any jobs that attempt to enter a queue that has reached its capacity should be sent to an overflow
 *  area and leave the system.
 *
 *  The system has 3 conveyors to assist with moving the jobs within the system.  The jobs require 2 cells when using the conveyor.
 *  The time taken to load or unload the job on or off the conveyors is assumed to be negligible.
 *
 *  The first conveyor (ArrivalConveyor) moves items from the area entrance to the drilling station.
 *  The second conveyor (LoopConveyor) moves items between the work stations.
 *  The third conveyor (ExitConveyor) moves items from inspection to the exit area.
 *
 *  The configuration of the conveyors is as follows:
 *
 *  ArrivalConveyor
 *      Accumulating
 *      From arrival to drilling 60 feet
 *      Initial velocity = 25 feet/minute
 *      Cell size = 10 feet
 *      Maximum number of cells that a part will use = 2
 *
 *  LoopConveyor
 *      Non-accumulating, circular
 *      (Drilling to Milling = 70 feet)
 *      (Milling to Planing = 90 feet)
 *      (Planing to Grinding = 50 feet)
 *      (Grinding to Inspection = 180 feet)
 *      (Inspection to Drilling = 250 feet)
 *      Initial velocity = 30 feet/minute
 *      Cell size = 10 feet
 *      Maximum number of cells that a part will use = 2
 *
 *  ExitConveyor
 *      Accumulating
 *      From inspection to exit = 100 feet
 *      Initial velocity = 45 feet/minute
 *      Cell size = 10 feet
 *      Maximum number of cells that a part will use = 2
 *
 *  Simulate the system for 40 hours and estimate the following:
 *  1. Number of jobs completed
 *  2. Utilization of the resources
 *  3. The number of jobs that overflow due to queue capacity
 *  4. Average number of jobs in the queues
 *  5. Number of jobs that are scrapped
 *  6. Number of reworked jobs
 *  7. Average time in the system for a job
 *  8. Time between job exits
 *  9. Utilization of space on the conveyors
 *
 */
class ConveyorExample(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {
}