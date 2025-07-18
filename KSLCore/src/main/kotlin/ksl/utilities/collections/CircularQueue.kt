package ksl.utilities.collections

/**
 * A circular queue is a linear data structure in which the operations are performed based on FIFO
 * (First In First Out) principle, and the last position is connected back to the first position
 * to make a circle. It is also called “Ring Buffer.”
 */
class CircularQueue<T>(private val maxSize: Int) {
    private val elements: Array<T?> = arrayOfNulls(maxSize)
    private var front = 0
    private var rear = -1
    private var currentSize = 0

    fun enqueue(item: T): Boolean {
        if (isFull()) {
            return false // Queue is full
        }
        rear = (rear + 1) % maxSize
        elements[rear] = item
        currentSize++
        return true
    }

    fun dequeue(): T? {
        if (isEmpty()) {
            return null // Queue is empty
        }
        val item = elements[front]
        elements[front] = null // Optional: clear the dequeued element
        front = (front + 1) % maxSize
        currentSize--
        return item
    }

    fun isFull(): Boolean {
        return currentSize == maxSize
    }

    fun isEmpty(): Boolean {
        return currentSize == 0
    }

    fun size(): Int {
        return currentSize
    }

    fun clear() {
        for (i in 0 until maxSize) {
            elements[i] = null
        }
        front = 0
        rear = -1
        currentSize = 0
    }
}