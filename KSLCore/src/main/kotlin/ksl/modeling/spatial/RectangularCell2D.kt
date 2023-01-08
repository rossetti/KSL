package ksl.modeling.spatial

import ksl.simulation.ModelElement
import ksl.utilities.KSLArrays
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.util.*

/**
 * Represents a basic unit of a RectangularGridSpatialModel
 *
 * @author rossetti
 */
class RectangularCell2D internal constructor(grid: RectangularGridSpatialModel2D?, row: Int, col: Int) {
    /**
     * @return Returns the cell's Row Index.
     */
    val rowIndex: Int

    /**
     * @return Returns the column index
     */
    val columnIndex: Int

    /**
     * @return Returns the cell's Width.
     */
    val width: Double

    /**
     * @return Returns the height of the cell
     */
    val height: Double

    private val myRectangle: Rectangle2D

    /**
     * Can be used to check if the cell is available or not. For example, this
     * can be used to see if the cell is available for traversal.
     *
     * @return true means available
     */
    var isAvailable: Boolean
        private set

    /**
     * A cell must be inside a grid, this provides a reference to the cell's
     * containing grid
     */
    private val myParentRectangularGrid2D: RectangularGridSpatialModel2D
    private val mySpatialElements: MutableList<SpatialElementIfc> = mutableListOf()

    init {
        requireNotNull(grid) { "The grid must not be null" }
        myParentRectangularGrid2D = grid
        rowIndex = row
        columnIndex = col
        width = grid.getCellWidth()
        height = grid.getCellHeight()
        // create and set the rectangle
        val points: Array<Array<Point2D>> = grid.getPoints()
        myRectangle = Rectangle2D.Double(points[row][col].x, points[row][col].y, width, height)
        mySpatialElements = LinkedList<SpatialElementIfc>()
        isAvailable = false
    }

    /** The row major index for this cell, based on getRowMajorIndex() of
     * RectangularGridSpatialModel2D
     *
     * @return the index
     */
    val rowMajorIndex: Int
        get() = myParentRectangularGrid2D.getRowMajorIndex(rowIndex, columnIndex)

    /**
     * Can be used to check if the cell is available or not. For example, this
     * can be used to see if the cell is available for traversal.
     *
     * @param flag true means available
     */
    fun setAvailability(flag: Boolean) {
        isAvailable = flag
    }

    val rowColName: String
        get() {
            val sb = StringBuilder()
            sb.append("Cell(")
            sb.append(rowIndex)
            sb.append(",")
            sb.append(columnIndex)
            sb.append(")")
            return sb.toString()
        }

    /**
     *
     * @return the number of spatial elements in the cell
     */
    val numSpatialElements: Int
        get() = mySpatialElements.size

    /**
     * Gets a list of elements of the target class that are in the cell
     *
     * @param <T> the type
     * @param targetClass the class type
     * @return the list
    </T> */
    fun <T> getElements(targetClass: Class<T>): List<T> {
        return KSLArrays.getElements(mySpatialElements, targetClass)
    }

    /** Gets a list of model elements of the target class that are in the cell
     * This uses getModelElements() as the basis for the search
     * @param <T> the type
     * @param targetClass the class type
     * @return the list
    </T> */
    fun <T> getModelElementsOfType(targetClass: Class<T>): List<T> {
        return KSLArrays.getElements(modelElements, targetClass)
    }

    /**
     * Counts the number of ModelElements of the provided class type that are in
     * the cell. Use X.class for the search, where X is a valid class name.
     *
     * @param targetClass
     * @return the count
     */
    fun countModelElements(targetClass: Class<*>): Int {
        return KSLArrays.countElements(modelElements, targetClass)
    }

    /**
     * Counts the number of SpatialElements of the provided class type that are
     * in the cell. Use X.class for the search, where X is a valid class name.
     *
     * @param targetClass
     * @return the count
     */
    fun countSpatialElements(targetClass: Class<*>): Int {
        return KSLArrays.countElements(mySpatialElements, targetClass)
    }

    /**
     * Returns a list of the ModelElements attached to any spatial elements
     * within the cell.
     *
     * @return a list of the ModelElements attached to any spatial elements
     * within the cell
     */
    val modelElements: List<Any>
        get() {
            val list: MutableList<ModelElement> = ArrayList<ModelElement>()
            for (se in mySpatialElements) {
                //TODO
//                if (se.getModelElement() != null) {
//                    list.add(se.getModelElement())
//                }
            }
            return list
        }

    /**
     * Returns an unmodifiable view of the spatial elements in this cell The
     * list is unmodifiable, i.e. you can't change the list, but you can still
     * change the elements in the list. WARNING: Don't change the elements
     * unless you really know what you are doing.
     *
     * @return
     */
    val unmodifiableSpatialElements: List<Any>
        get() = Collections.unmodifiableList(mySpatialElements)

    /**
     * This is a copy. The underlying list of spatial elements might change
     *
     * @return a copy of the current list of spatial elements
     */
    val listOfSpatialElements: List<Any>
        get() = mySpatialElements

    /**
     * Returns an array containing the cells associated with the 1st Moore
     * neighborhood for the cell.
     *
     * @param neighborhood
     */
    fun getMooreNeighborhood(neighborhood: Array<Array<RectangularCell2D?>?>?) {
        myParentRectangularGrid2D.mooreNeighborhood(this, neighborhood)
    }

    /**
     * Converts the cell to a string
     *
     * @return
     */
    override fun toString(): String {
        val s = StringBuilder()
        s.append("Cell\n")
        s.append("row = ").append(rowIndex).append(" : ")
        s.append("column = ").append(columnIndex).appendLine()
        s.append("width = ").append(width).append(" : ")
        s.append("height = ").append(height).appendLine()
        s.append("minimum x = ").append(minX).append(" : ")
        s.append("maximum x = ").append(maxX).appendLine()
        s.append("center x = ").append(centerX).append(" : ")
        s.append("center y = ").append(centerY).appendLine()
        s.append("minimum y = ").append(minY).append(" : ")
        s.append("maximum y = ").append(maxY).appendLine()
        s.append("UL coordinate : ").append(upperLeftCoordinate).appendLine()
        s.append("UR coordinate : ").append(upperRightCoordinate).appendLine()
        s.append("Center coordinate : ").append(centerCoordinate).appendLine()
        s.append("LL coordinate : ").append(lowerLeftCoordinate).appendLine()
        s.append("LR coordinate : ").append(lowerRightCoordinate).appendLine()
        s.append("Availability : ").append(isAvailable).appendLine()
        s.append("Spatial elements in the cell: ")
        if (mySpatialElements.isEmpty()) {
            s.append("NONE")
        }
        s.append(System.lineSeparator())
        for (se in mySpatialElements) {
            s.append(se)
            s.appendLine()
        }
        s.appendLine()
        return s.toString()
    }

    /**
     * Returns the RectangularGridSpatialModel2D that contains this cell
     *
     * @return
     */
    val parentRectangularGrid2D: RectangularGridSpatialModel2D
        get() = myParentRectangularGrid2D

    /**
     * Checks if x and y are in this cell
     *
     * @param x
     * @param y
     * @return
     */
    fun contains(x: Double, y: Double): Boolean {
        return myRectangle.contains(x, y)
    }

    val upperLeftCoordinate: CoordinateIfc
        get() = myParentRectangularGrid2D.getCoordinate(x, y)
    val centerCoordinate: CoordinateIfc
        get() = myParentRectangularGrid2D.getCoordinate(centerX, centerY)
    val upperRightCoordinate: CoordinateIfc
        get() {
            val x = x + width
            val y = y
            return myParentRectangularGrid2D.getCoordinate(x, y)
        }
    val lowerLeftCoordinate: CoordinateIfc
        get() {
            val x = x
            val y = y + height
            return myParentRectangularGrid2D.getCoordinate(x, y)
        }
    val lowerRightCoordinate: CoordinateIfc
        get() {
            val x = x + width
            val y = y + height
            return myParentRectangularGrid2D.getCoordinate(x, y)
        }

    /**
     * The x-coordinate of the upper left corner of the rectangle for the cell
     *
     * @return
     */
    val x: Double
        get() = myRectangle.x

    /**
     * The y-coordinate of the upper left corner of the rectangle for the cell
     *
     * @return
     */
    val y: Double
        get() = myRectangle.y

    /**
     * The x-coordinate of the center of the cell
     *
     * @return
     */
    val centerX: Double
        get() = myRectangle.centerX

    /**
     * The y-coordinate of the center of the cell
     *
     * @return
     */
    val centerY: Double
        get() = myRectangle.centerY

    /**
     * The x-coordinate of the maximum x still within the cell
     *
     * @return
     */
    val maxX: Double
        get() = myRectangle.maxX

    /**
     * The y-coordinate of the maximum y still within the cell
     *
     * @return
     */
    val maxY: Double
        get() = myRectangle.maxY

    /**
     * The x-coordinate of the minimum x still within the cell
     *
     * @return
     */
    val minX: Double
        get() = myRectangle.minX

    /**
     * The y-coordinate of the minimum y still within the cell
     *
     * @return
     */
    val minY: Double
        get() = myRectangle.minY

    /**
     * Add the spatial element to the cell
     *
     * @param element
     */
    protected fun addSpatialElement(element: SpatialElementIfc) {
        mySpatialElements.add(element)
    }

    /**
     * Removes the spatial element from the cell
     *
     * @param element
     * @return
     */
    protected fun removeSpatialElement(element: SpatialElementIfc): Boolean {
        return mySpatialElements.remove(element)
    }
}