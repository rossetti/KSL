package ksl.modeling.entity

import ksl.modeling.entity.Conveyor.Cell

/**
 *  A conveyor request represents the holding of cells on a conveyor and acts as
 *  a "ticket" to use the conveyor.  Once an entity has a conveyor request, the entity
 *  has control over the cells at the start of the segment associated with the entry
 *  location along the conveyor.  After receiving a request to
 *  access the conveyor, the entity can either ride on the conveyor or exit. The conveyor
 *  request blocks at the point of access until riding or exiting. The request is placed
 *  in the blocking entry state.  When the entity
 *  asks to ride the conveyor, then the request will be placed in the riding state. If the entity
 *  never rides the conveyor, then the request stays in the blocking entry state.  The property isWaitingForEntry
 *  indicates that the conveyor request is waiting to be allowed to block the entry cell of the conveyor
 *  at its current location. Once the conveyor request is used to ride the conveyor, the isWaitingToConvey property will
 *  report false. The isBlockingEntry property will report true until the request begins
 *  riding.  Once the request reaches its destination, the isBlockingExit property will be true and the
 *  request is in the blocking exit state.  When the request exits the conveyor, the isCompleted property is true
 *  and the request is in the completed state.  Once in the completed state, the request can no longer be used
 *  for any interaction with the conveyor.
 */
interface ConveyorRequestIfc {

    val isWaitingForEntry: Boolean
    val isBlockingEntry: Boolean
    val isRiding: Boolean
    val isBlockingExit: Boolean
    val isCompleted: Boolean

    val status: Conveyor.ItemStatus

    /**
     *  The location where the entity first accessed the conveyor
     */
    val entryLocation: String

    /**
     * The entity that needs to use the conveyor
     */
    val entity: ProcessModel.Entity

    /**
     *  The number of cells needed when riding on the conveyor
     */
    val numCellsNeeded: Int

    /**
     * The current location of the entity. This is assigned
     * when the entity arrives at the end of a segment
     */
    val currentLocation: String

    /**
     * The final location where the entity wants to visit on the conveyor
     */
    val destination: String?

    /**
     *  The number of cells currently occupied on the conveyor.
     *  This may be different from numCellsAllocated because an
     *  allocation does not need to convey. It can be used to block
     *  the conveyor at one of its entrance locations
     */
    val numCellsOccupied: Int

    /**
     *  True if the item occupies cells. After exiting the conveyor
     *  the item does not occupy any cells
     */
    val occupiesCells: Boolean

    /**
     *  The conveyor the entity is using
     */
    val conveyor: Conveyor

    /**
     * The object riding on the conveyor has a front (facing towards) its destination
     * and an end (facing where it originated). This cell is the furthermost cell
     * occupied towards the front (in the direction of travel)
     */
    val frontCell: Conveyor.Cell?

    /**
     *  This cell is the furthermost cell occupied by the object towards the where
     *  it originated.
     */
    val rearCell: Conveyor.Cell?

    /**
     *  True if the item has reached the last cell of the current segment
     */
    val hasReachedAnExitCell: Boolean

    /**
     *  Can be used when the entity is resumed
     */
    val accessResumePriority: Int

    /**
     *  While riding, this is the location where the entity is heading
     */
    val plannedLocation: String?
        get() {
            return if (rearCell == null) {
                null
            } else {
                rearCell!!.segment.exitLocation
            }
        }

    /**
     *  True if the entity has reached its destination
     */
    val hasReachedDestination: Boolean

    /**
     *  The conveyor cell on which the item entered the conveyor
     */
    val entryCell: Cell

    fun asString(): String {
        val sb = StringBuilder()
        sb.appendLine("Conveyor Request for entity ${entity.id}")
        sb.appendLine("conveyor: ${conveyor.name}, entry location: ${entryLocation}, current location: ${currentLocation}, destination: ${destination}")
        sb.appendLine("Front cell = ${frontCell?.cellNumber}, Rear cell = ${rearCell?.cellNumber}")
        sb.appendLine("Has reached destination = $hasReachedDestination, Has reached exit cell $hasReachedAnExitCell")
        sb.appendLine("status = $status, num cell occupied = $numCellsOccupied, num cells needed = $numCellsNeeded")
        sb.appendLine("isRiding = $isRiding, isCompleted = $isCompleted, isBlockingExit = $isBlockingExit, isBlockingEntry = $isBlockingEntry, isWaitingForEntry = $isWaitingForEntry")
        return sb.toString()
    }
}