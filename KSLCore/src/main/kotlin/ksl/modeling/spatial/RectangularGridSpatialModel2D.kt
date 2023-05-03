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

package ksl.modeling.spatial

import ksl.simulation.ModelElement
import ksl.utilities.KSLArrays
import ksl.utilities.math.KSLMath
import ksl.utilities.to2DArray
import java.awt.Shape
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.sqrt

/**
 * Creates a grid in the 2D plane. The grid is based on the standard user
 * coordinate system, with (x,y) = (0,0) being the upper left most corner
 * point, with the x-axis going from left to right and the y-axis going from
 * the top down
 *
 * @param upperX The x coordinate of the upper left most corner point
 * @param upperY The y coordinate of the upper left most corner point
 * @param width The width (along the x-axis) of the grid
 * @param height The height (along the y-axis) of the grid
 * @param numRows The number of rows in the grid (0-based)
 * @param numCols The number of columns in the grid (0-based)
 */
class RectangularGridSpatialModel2D(
    width: Double = Double.MAX_VALUE,
    height: Double = Double.MAX_VALUE,
    numRows: Int = 1,
    numCols: Int = 1,
    upperX: Double = 0.0,
    upperY: Double = 0.0,
) : SpatialModel() {
    override var defaultLocation: LocationIfc = GridPoint(0.0, 0.0, "defaultLocation")
    init {
        require(numRows >= 1) { "The number of rows must be >=1" }
        require(numCols >= 1) { "The number of rows must be >=1" }
        require(width > 0.0) { "The width must be > 0.0" }
        require(height > 0.0) { "The height must be > 0.0" }
    }

    var defaultLocationPrecision = KSLMath.defaultNumericalPrecision
        set(precision) {
            require(precision > 0.0) { "The precision must be > 0.0." }
            field = precision
        }

    /**
     * The width of the grid in user dimensions
     */
    private val myWidth = width

    /**
     * The height of the grid in user dimensions
     */
    private val myHeight = height

    /**
     * A reference to the rectangle that forms the outer edge of the grid
     */
    private val myOuterRectangle: Rectangle2D = Rectangle2D.Double(upperX, upperY, width, height)

    /**
     * The number of rows in the grid, the grid is zero based (the first row is
     * the 0th row)
     */
    private val myNumRows = numRows

    /**
     * The number of columns in the grid, the grid is zero based (the first
     * column is the 0th column)
     */
    private val myNumCols = numCols

    /**
     * The width of the cells in the grid
     */
    private val myCellWidth = myWidth / numCols

    /**
     * The height of the cells in the grid
     */
    private val myCellHeight = myHeight / numRows

    /**
     * An 2-d array of points forming the grid, point[0][0] = left upper corner
     * point
     */
    private val myPoints: Array<Array<Point2D.Double>>

    init {
        var y = upperY
        val oList = mutableListOf<MutableList<Point2D.Double>>()
        for (i in 0..myNumRows) {
            var x = upperX
            val list = mutableListOf<Point2D.Double>()
            for (j in 0..myNumCols) {
                list.add(Point2D.Double(x, y))
                x = x + myCellWidth
            }
            y = y + myCellHeight
            oList.add(list)
        }
        myPoints = to2DArray(oList)
    }

    private val myUpperLeftCornerPt: Point2D = myPoints[0][0]

    /**
     * The upper left corner point for the grid
     */
    val upperLeftCorner: Point2D
        get() = Point2D.Double(myUpperLeftCornerPt.x, myUpperLeftCornerPt.y)

    private val myLowerLeftCornerPt: Point2D = myPoints[myNumRows][0]

    /**
     * The lower left corner point for the grid
     */
    val lowerLeftCorner: Point2D
        get() = Point2D.Double(myLowerLeftCornerPt.x, myLowerLeftCornerPt.y)

    private val myUpperRightCornerPt: Point2D = myPoints[0][myNumCols]

    /**
     * The upper right corner point for the grid
     */
    val upperRightCorner: Point2D
        get() = Point2D.Double(myUpperRightCornerPt.x, myUpperRightCornerPt.y)

    private val myLowerRightCornerPt: Point2D = myPoints[myNumRows][myNumCols]

    /**
     * The lower right corner point for the grid
     */
    val lowerRightCorner: Point2D
        get() = Point2D.Double(myLowerRightCornerPt.x, myLowerRightCornerPt.y)

    /**
     * The line at the top of the grid
     */
    val topLine: Line2D
        get() = Line2D.Double(myUpperLeftCornerPt, myUpperRightCornerPt)

    /**
     * The line at the bottom of the grid
     */
    val bottomLine: Line2D
        get() = Line2D.Double(myLowerLeftCornerPt, myLowerRightCornerPt)

    /**
     * The line on the right side of the grid
     */
    val rightLine: Line2D
        get() = Line2D.Double(myUpperRightCornerPt, myLowerRightCornerPt)

    /**
     * The line on the left side of the grid
     */
    val leftLine: Line2D
        get() = Line2D.Double(myUpperLeftCornerPt, myLowerLeftCornerPt)

    /**
     * A reference to the path that forms the grid including all line segments
     */
    private var myPath: GeneralPath = GeneralPath()
    val shape: Shape
        get() = GeneralPath(myPath)

    /**
     * An 2-d array of the horizontal line segments in the grid hline[0][0] =
     * point[0][0] -- point[0][1] and so forth
     */
    private var myHorzLines: Array<Array<Line2D>> = Array(myNumRows + 1) { i ->
        Array(myNumCols) { j ->
            Line2D.Double(myPoints[i][j], myPoints[i][j + 1])
        }
    }

    /**
     * An 2-d array of the vertical line segments in the grid vline[0][0] =
     * point[0][0] -- point[1][0] and so forth
     */
    private var myVertLines: Array<Array<Line2D>> = Array(myNumRows) { i ->
        Array(myNumCols + 1) { j ->
            Line2D.Double(myPoints[i][j], myPoints[i + 1][j])
        }
    }

    init {
        for (j in 0 until myNumCols + 1) {
            for (i in 0 until myNumRows) {
                myVertLines[i][j] = Line2D.Double(myPoints[i][j], myPoints[i + 1][j])
            }
        }
    }

    /**
     * An 2-d array of the cells forming the grid cell[0][0] = upper left most
     * cell with left corner point[0][0]
     */
    private var myCells: Array<Array<RectangularCell2D>> = Array(myNumRows) { row ->
        Array(myNumCols) { col ->
            RectangularCell2D(row, col)
        }
    }

    /**
     * A List of the cells in the grid formed row by row
     */
    private var myCellList: ArrayList<RectangularCell2D> = ArrayList(myNumRows * myNumCols)

    init {
        // make the vertical line segments
        // make the cells from the points and lines
        for (i in 0 until myNumRows) {
            for (j in 0 until myNumCols) {
                myCellList.add(myCells[i][j])
            }
        }
        // set the initial point on the path
        myPath.moveTo(myUpperLeftCornerPt.x.toFloat(), myUpperLeftCornerPt.y.toFloat())
        // add the horizontal lines
        for (i in 0 until myNumRows + 1) {
            for (j in 0 until myNumCols) {
                myPath.append(myHorzLines[i][j], false)
            }
        }
        // add the vertical lines
        for (j in 0 until myNumCols + 1) {
            for (i in 0 until myNumRows) {
                myPath.append(myVertLines[i][j], false)
            }
        }
        // close the path
        myPath.closePath()

    }

    /**
     * The cells in the grid as a list. The cells are accesses by rows
     * (row, col): (0,0), then (0,1), etc 0th row first,
     */
    val cells: List<RectangularCell2D>
        get() = myCellList

    /**
     * An iterator over the cells in the grid. The cells are accesses by rows
     * (row, col): (0,0), then (0,1), etc 0th row first,
     */
    val cellIterator: Iterator<RectangularCell2D>
        get() {
            return myCellList.iterator()
        }

    /**
     * The cell at this [row], [col]. Null is returned if
     * the row or column is outside the grid.
     */
    fun cell(row: Int, col: Int): RectangularCell2D? {
        return if ((row < 0) || (row >= myNumRows)) {
            null
        } else if ((col < 0) || (col >= myNumCols)) {
            null
        } else {
            myCells[row][col]
        }
    }

    /**
     * The cell that contains this ([x],[y]) coordinate or null if no cell
     * contains the coordinate.
     */
    fun cell(x: Double, y: Double): RectangularCell2D? {
        if (!contains(x, y)) {
            return null
        }
        val col = (x / myCellWidth).toInt()
        val row = (y / myCellHeight).toInt()
        return cell(row.toDouble(), col.toDouble())
    }

    /**
     * Returns the cell that the [location] is in or null
     */
    fun cell(location: LocationIfc): RectangularCell2D? {
        if (!isValid(location)) {
            return null
        }
        val loc = location as GridPoint
        return cell(loc.x, loc.y)
    }

    /**
     * Returns the cell that the [element] is in or null
     */
    fun cell(element: SpatialElementIfc): RectangularCell2D? {
        if (!isValid(element)) {
            return null
        }
        return cell(element.currentLocation)
    }

    /**
     * The number of elements in the cell containing [spatialElement]
     */
    fun numElementsInCell(spatialElement: SpatialElementIfc): Int {
        val cell = cell(spatialElement) ?: return 0
        return cell.numSpatialElements
    }

    /**
     * The number of elements in the cell containing [location]
     */
    fun numElementsInCell(location: LocationIfc): Int {
        val cell = cell(location) ?: return 0
        return cell.numSpatialElements
    }

    /**
     * The number of elements in the cell containing [x] and [y]
     */
    fun numElementsInCell(x: Double, y: Double): Int {
        val cell = cell(x, y) ?: return 0
        return cell.numSpatialElements
    }

    /**
     * The elements in the cell containing [spatialElement] or an empty list.
     */
    fun elementsInCell(spatialElement: SpatialElementIfc): List<SpatialElementIfc> {
        val cell = cell(spatialElement) ?: return emptyList()
        return cell.spatialElements
    }

    /**
     * The elements in the cell containing [location] or an empty list
     */
    fun elementsInCell(location: LocationIfc): List<SpatialElementIfc> {
        val cell = cell(location) ?: return emptyList()
        return cell.spatialElements
    }

    /**
     * The elements in the cell that contains [x] and [y] or an empty list
     */
    fun elementsInCell(x: Double, y: Double): List<SpatialElementIfc> {
        val cell = cell(x, y) ?: return emptyList()
        return cell.spatialElements
    }

    /**
     * Checks if the x and y values are in the grid
     *
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @return true if in the grid
     */
    fun contains(x: Double, y: Double): Boolean {
        val x0 = myOuterRectangle.x
        val y0 = myOuterRectangle.y
        return (x >= x0) && (y >= y0) && (x <= (x0 + myWidth)) && (y <= (y0 + myHeight))
    }

    /** The row major index is row(number of columns) + col + 1
     * Labeling starts at 1 and goes by row (across columns). For example
     * for a 3 by 3 grid
     * [1, 2, 3]
     * [4, 5, 6]
     * [7, 8, 9]
     * @param row the row
     * @param col the column
     * @return the row major index of the cell
     */
    fun getRowMajorIndex(row: Int, col: Int): Int {
        require((row < 0 || (row) < myNumRows)) { "row was < 0 or >= #rows" }
        require((col < 0 || (col) < myNumCols)) { "col was < 0 or >= #cols" }
        return row * myNumCols + col + 1
    }

    override fun updatedElementLocation(element: SpatialElementIfc) {
        super.updatedElementLocation(element)
        val p = element.previousLocation as AbstractLocation
        val c = element.currentLocation as AbstractLocation
        val prevCell = cell(p)!!
        val nextCell = cell(c)!!
        if (prevCell != nextCell) {
            // change in cell
            prevCell.removeSpatialElement(element)
            nextCell.addSpatialElement(element)
            status = Status.CELL_CHANGED
            notifyObservers(element)
        }
    }

    override fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double {
        require(isValid(fromLocation)) { "The location ${fromLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(toLocation)) { "The location ${toLocation.name} is not a valid location for spatial model ${this.name}" }
        val f = fromLocation as GridPoint
        val t = toLocation as GridPoint
        val dx = f.x - t.x
        val dy = f.y - t.y
        return sqrt(dx * dx + dy * dy)
    }

    override fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean {
        require(isValid(firstLocation)) { "The location ${firstLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(secondLocation)) { "The location ${secondLocation.name} is not a valid location for spatial model ${this.name}" }
        val f = firstLocation as GridPoint
        val t = secondLocation as GridPoint
        val b1 = KSLMath.equal(f.x, t.x, defaultLocationPrecision)
        val b2 = KSLMath.equal(f.y, t.y, defaultLocationPrecision)
        return b1 && b2
    }

    override fun track(element: SpatialElement) {
        // add it to the model
        super.track(element)
        // add it to the cell within the model
        val c = element.currentLocation as GridPoint
        // the location must be related to a cell, since the location was made by this model
        val cell: RectangularCell2D = cell(c.x, c.y)!!
        cell.addSpatialElement(element)
    }

    override fun stopTracking(element: SpatialElement) {
        super.stopTracking(element)
        // first remove it from the cell
        val c = element.currentLocation as GridPoint
        // the location must be related to a cell, since the location was made by this model
        val cell: RectangularCell2D = cell(c.x, c.y)!!
        cell.removeSpatialElement(element)
    }

//    override fun transferSpatialElement(
//        element: SpatialElement,
//        newSpatialModel: SpatialModel,
//        newLocation: LocationIfc
//    ): SpatialElementIfc {
//        // first remove it from the cell
//        val c = element.currentLocation as Location
//        // the location must be related to a cell, since the location was made by this model
//        val cell: RectangularCell2D = cell(c.x, c.y)!!
//        cell.removeSpatialElement(element)
//        // now make the transfer
//        return super.transferSpatialElement(element, newSpatialModel, newLocation)
//    }

    /** Represents a location within this spatial model.
     *
     * @param aName the name of the location, will be assigned based on ID_id if null
     */
    inner class GridPoint(val x: Double, val y: Double, aName: String? = null) : AbstractLocation(aName) {
        init {
            require(contains(x, y)) { "The grid does not contain the supplied x = $x and y = $y" }
        }

        override val spatialModel: SpatialModel = this@RectangularGridSpatialModel2D
        override fun toString(): String {
            return "GridPoint(x=$x, y=$y, id=$id, name='$name', spatial model=${spatialModel.name})"
        }
    }

    /**
     * Returns an array with the 1st order Moore neighborhood for the
     * given core cell.
     *
     * set the top row of the neighborhood neighborhood[0][0] = getCell(i-1,
     * j-1) neighborhood[0][1] = getCell(i-1, j) neighborhood[0][2] =
     * getCell(i-1, j+1)
     *
     * set the middle row of the neighborhood neighborhood[1][0] = getCell(i,
     * j-1) neighborhood[1][1] = getCell(i, j) neighborhood[1][2] = getCell(i,
     * j+1)
     *
     * set the bottom row of the neighborhood neighborhood[2][0] =
     * getCell(i+1,j-1) neighborhood[2][1] = getCell(i+1, j) neighborhood[2][2]
     * = getCell(i+1, j+1)
     *
     * @param coreCell
     */
    fun mooreNeighborhood(coreCell: RectangularCell2D): Array<Array<RectangularCell2D?>> {
        if (this != coreCell.grid) {
            return arrayOf(
                arrayOfNulls(3),
                arrayOfNulls(3),
                arrayOfNulls(3)
            )
        }
        // get the core cell's indices
        val i: Int = coreCell.rowIndex
        val j: Int = coreCell.columnIndex
        val n = arrayOf(
            arrayOf(cell(i - 1, j - 1), cell(i - 1, j), cell(i - 1, j + 1)),
            arrayOf(cell(i, j - 1), cell(i, j), cell(i, j + 1)),
            arrayOf(cell(i + 1, j - 1), cell(i + 1, j), cell(i + 1, j + 1))
        )
        return n
    }

    /**
     * Fills the supplied array with the 1st order Moore neighborhood for the
     * given core cell.
     *
     * set the top row of the neighborhood neighborhood[0][0] = getCell(i-1,
     * j-1) neighborhood[0][1] = getCell(i-1, j) neighborhood[0][2] =
     * getCell(i-1, j+1)
     *
     * set the middle row of the neighborhood neighborhood[1][0] = getCell(i,
     * j-1) neighborhood[1][1] = getCell(i, j) neighborhood[1][2] = getCell(i,
     * j+1)
     *
     * set the bottom row of the neighborhood neighborhood[2][0] =
     * getCell(i+1,j-1) neighborhood[2][1] = getCell(i+1, j) neighborhood[2][2]
     * = getCell(i+1, j+1)
     *
     * @param coreCell
     * @param neighborhood
     */
    fun mooreNeighborhood(
        coreCell: RectangularCell2D,
        neighborhood: Array<Array<RectangularCell2D?>>
    ) {
        require(neighborhood.size >= 3) { "Row size of the array must be 3." }
        for (i in neighborhood.indices) {
            require(neighborhood[i].size >= 3) { "Column size of the array must be 3." }
        }

        // nullify the neighborhood
        for (i in 0..2) {
            for (j in 0..2) {
                neighborhood[i][j] = null
            }
        }

        if (this != coreCell.grid) {
            return
        }

        // get the core cell's indices
        val i: Int = coreCell.rowIndex
        val j: Int = coreCell.columnIndex

        // set the top row of the neighborhood
        neighborhood[0][0] = cell(i - 1, j - 1)
        neighborhood[0][1] = cell(i - 1, j)
        neighborhood[0][2] = cell(i - 1, j + 1)

        // set the middle row of the neighborhood
        neighborhood[1][0] = cell(i, j - 1)
        neighborhood[1][1] = cell(i, j)
        neighborhood[1][2] = cell(i, j + 1)

        // set the bottom row of the neighborhood
        neighborhood[2][0] = cell(i + 1, j - 1)
        neighborhood[2][1] = cell(i + 1, j)
        neighborhood[2][2] = cell(i + 1, j + 1)
    }

    private fun addCell(i: Int, j: Int, list: MutableList<RectangularCell2D>) {
        val cell = cell(i, j)
        if (cell != null) {
            list.add(cell)
        }
    }

    /**
     * Includes the non-null cells in the neighborhood into a List
     *
     * @param coreCell the core cell of the neighborhood to translate
     * @param includeCore true includes the core in the list, false does not
     * @return the list of cells in the neighborhood
     */
    fun mooreNeighborhoodAsList(coreCell: RectangularCell2D, includeCore: Boolean = false): List<RectangularCell2D> {
        if (this != coreCell.grid) {
            return emptyList()
        }
        val list = mutableListOf<RectangularCell2D>()
        // get the core cell's indices
        val i: Int = coreCell.rowIndex
        val j: Int = coreCell.columnIndex
        // set the top row of the neighborhood
        addCell(i - 1, j - 1, list)
        addCell(i - 1, j, list)
        addCell(i - 1, j + 1, list)
        // set the middle row of the neighborhood
        addCell(i, j - 1, list)
        if (includeCore) {
            addCell(i, j, list)
        }
        addCell(i, j + 1, list)
        // set the bottom row of the neighborhood
        addCell(i + 1, j - 1, list)
        addCell(i + 1, j, list)
        addCell(i + 1, j + 1, list)
        return list
    }

    override fun toString(): String {
        val s = StringBuilder()
        s.append("Grid").appendLine()
        s.append("width = ").append(myWidth).append(" height = ").append(myHeight).appendLine()
        s.appendLine()
        s.append("ULPT = ").append(myUpperLeftCornerPt).append("---->")
        s.append("URPT = ").append(myUpperRightCornerPt).appendLine()
        s.append("LLPT = ").append(myLowerLeftCornerPt).append("---->")
        s.append("LRPT = ").append(myLowerRightCornerPt).appendLine()
        s.appendLine()
        s.append("TopLine = ").append(topLine.p1).append("---->").append(topLine.p2)
        s.appendLine()
        s.append("BottomLine = ").append(bottomLine.p1).append("---->").append(bottomLine.p2)
        s.appendLine()
        s.append("LeftLine = ").append(leftLine.p1).append("---->").append(leftLine.p2)
        s.appendLine()
        s.append("RightLine = ").append(rightLine.p1).append("---->").append(rightLine.p2)
        s.appendLine()
        s.appendLine()
        s.appendLine("Points")
        for (i in 0..myNumRows) {
            for (j in 0..myNumCols) {
                s.append("Point[i=").append(i).append("][j=").append(j).append("]= ").append(myPoints[i][j])
                s.appendLine()
            }
        }
        s.appendLine()
        s.appendLine("Horizontal lines")
        for (i in 0 until myNumRows + 1) {
            for (j in 0 until myNumCols) {
                s.append(myHorzLines[i][j].p1).append("---->").append(myHorzLines[i][j].p2).appendLine()
            }
        }
        s.appendLine()
        s.appendLine("Vertical lines")
        for (j in 0 until myNumCols + 1) {
            for (i in 0 until myNumRows) {
                s.append(myVertLines[i][j].p1).append("---->").append(myVertLines[i][j].p2).appendLine()
            }
        }
        s.appendLine()
        s.append("Cells")
        s.appendLine()
        for (i in 0 until myNumRows) {
            for (j in 0 until myNumCols) {
                s.append(myCells[i][j]).appendLine()
            }
        }
        return s.toString()
    }

    inner class RectangularCell2D(row: Int, col: Int) {

        /**
         * The grid that this cell is from
         */
        val grid = this@RectangularGridSpatialModel2D

        /**
         * @return Returns the cell's Row Index.
         */
        val rowIndex: Int = row

        /**
         * @return Returns the column index
         */
        val columnIndex: Int = col

        /**
         * @return Returns the cell's Width.
         */
        val width: Double = myCellWidth

        /**
         * @return Returns the height of the cell
         */
        val height: Double = myCellHeight

        private val myRectangle: Rectangle2D =
            Rectangle2D.Double(myPoints[row][col].x, myPoints[row][col].y, width, height)

        /**
         * Checks if x and y are in this cell
         *
         * @param x
         * @param y
         * @return true if (x,y) are in this cell
         */
        fun contains(x: Double, y: Double): Boolean {
            return myRectangle.contains(x, y)
        }

        /**
         * Can be used to check if the cell is available or not. For example, this
         * can be used to see if the cell is available for traversal.
         *
         * @return true means available
         */
        var isAvailable: Boolean = true
            internal set

        /**
         *
         * @return the number of spatial elements in the cell
         */
        val numSpatialElements: Int
            get() = mySpatialElements.size

        private val mySpatialElements: MutableList<SpatialElementIfc> = mutableListOf()
        val spatialElements: List<SpatialElementIfc>
            get() = mySpatialElements

        val rowColName: String = "Cell($rowIndex, $columnIndex)"

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

        val upperLeft: GridPoint = grid.GridPoint(x, y, "UpperLeft")

        val center: GridPoint = grid.GridPoint(centerX, centerY, "Center")

        val upperRight: GridPoint = grid.GridPoint(x + width, y, "UpperRight")

        val lowerLeft: GridPoint = grid.GridPoint(x, y + height, "LowerLeft")

        val lowerRight: GridPoint = grid.GridPoint(x + width, y + height, "LowerRight")

        /**
         * Gets a list of elements of the target class that are in the cell
         *
         * @param <T> the type
         * @param targetClass the class type
         * @return the list
        </T> */
        fun <T> elements(targetClass: Class<T>): List<T> {
            return KSLArrays.getElements(mySpatialElements, targetClass)
        }

        /** Gets a list of model elements of the target class that are in the cell
         * This uses getModelElements() as the basis for the search
         * @param <T> the type
         * @param targetClass the class type
         * @return the list
        </T> */
        fun <T> modelElementsOfType(targetClass: Class<T>): List<T> {
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
        val modelElements: List<ModelElement>
            get() {
                val list: MutableList<ModelElement> = mutableListOf()
                for (se in mySpatialElements) {
                    val me = se.modelElement
                    list.add(me)
                }
                return list
            }

        fun mooreNeighborhood(): Array<Array<RectangularCell2D?>> {
            return grid.mooreNeighborhood(this)
        }

        /**
         * Add the spatial element to the cell
         *
         * @param element
         */
        fun addSpatialElement(element: SpatialElementIfc) {
            mySpatialElements.add(element)
        }

        /**
         * Removes the spatial element from the cell
         *
         * @param element
         * @return
         */
        fun removeSpatialElement(element: SpatialElementIfc): Boolean {
            return mySpatialElements.remove(element)
        }

        /**
         * Converts the cell to a string
         *
         * @return
         */
        override fun toString(): String {
            val s = StringBuilder()
            s.append("Cell").appendLine()
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
            s.append("Upper Left : ").append(upperLeft).appendLine()
            s.append("Upper Right : ").append(upperRight).appendLine()
            s.append("Center : ").append(center).appendLine()
            s.append("Lower Left : ").append(lowerLeft).appendLine()
            s.append("Lower Right : ").append(lowerRight).appendLine()
            s.append("Availability : ").append(isAvailable).appendLine()
            s.append("Spatial elements in the cell: ")
            if (mySpatialElements.isEmpty()) {
                s.append("NONE")
            }
            s.appendLine()
            for (se in mySpatialElements) {
                s.append(se)
                s.appendLine()
            }
            s.appendLine()
            return s.toString()
        }
    }

    companion object {
        /**
         * Finds the cell that has the least number of spatial elements
         *
         * @param cells the cells to search, must not be empty
         * @return the minimum cell
         */
        fun findCellWithMinimumElements(cells: List<RectangularCell2D>): RectangularCell2D {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            var min = Int.MAX_VALUE
            var minCell: RectangularCell2D? = null
            for (cell in cells) {
                if (cell.numSpatialElements < min) {
                    min = cell.numSpatialElements
                    minCell = cell
                }
            }
            return minCell!!
        }

        /**
         * Across all the cells, what is the minimum number of elements in cells
         *
         * @param cells cells to search, must not be empty
         * @return the minimum
         */
        fun findMinimumNumberOfElements(cells: List<RectangularCell2D>): Int {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            var min = Int.MAX_VALUE
            for (cell in cells) {
                if (cell.numSpatialElements < min) {
                    min = cell.numSpatialElements
                }
            }
            return min
        }

        /**
         * Across all the cells, what is the maximum number of elements in cells
         *
         * @param cells cells to search, must not be empty
         * @return the maximum
         */
        fun findMaximumNumberOfElements(cells: List<RectangularCell2D>): Int {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            var max = Int.MIN_VALUE
            for (cell in cells) {
                if (cell.numSpatialElements > max) {
                    max = cell.numSpatialElements
                }
            }
            return max
        }

        /**
         * Across all the cells, which cells have the minimum number of elements in
         * cells
         *
         * @param cells the cells to search, must not be empty
         * @return a list of cells that have the minimum number of elements
         */
        fun findCellsWithMinimumElements(cells: List<RectangularCell2D>): List<RectangularCell2D> {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            val list = mutableListOf<RectangularCell2D>()
            val min = findMinimumNumberOfElements(cells)
            for (cell in cells) {
                if (cell.numSpatialElements == min) {
                    list.add(cell)
                }
            }
            return list
        }

        /**
         * Across all the cells, which cells have the maximum number of elements in
         * cells
         *
         * @param cells the cells to search, must not be empty
         * @return a list of cells that have the maximum number of elements
         */
        fun findCellsWithMaximumElements(cells: List<RectangularCell2D>): List<RectangularCell2D> {
            require(cells.isNotEmpty()) { "The supplied list of cells was empty!" }
            val list = mutableListOf<RectangularCell2D>()
            val max = findMaximumNumberOfElements(cells)
            for (cell in cells) {
                if (cell.numSpatialElements == max) {
                    list.add(cell)
                }
            }
            return list
        }

        /**
         * A comparator based on the number of elements in the cell
         *
         * @return A comparator based on the number of elements in the cell
         */
        fun numElementsComparator(): NumElementsComparator {
            return NumElementsComparator()
        }

        /**
         * Returns list of the cells sorted from smallest to largest based on
         * the number of spacial elements in the cells
         *
         * @param cells the cells to sort
         * @return a new list of the sorted cells
         */
        fun sortCellsByNumElements(cells: List<RectangularCell2D>): List<RectangularCell2D> {
            return cells.sortedWith(numElementsComparator())
        }

    }
}

class NumElementsComparator : Comparator<RectangularGridSpatialModel2D.RectangularCell2D> {
    override fun compare(
        o1: RectangularGridSpatialModel2D.RectangularCell2D,
        o2: RectangularGridSpatialModel2D.RectangularCell2D
    ): Int {
        if (o1.numSpatialElements < o2.numSpatialElements) {
            return -1
        }
        return if (o1.numSpatialElements > o2.numSpatialElements) {
            1
        } else 0
    }
}