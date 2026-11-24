package com.paintbymoonlight.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A painting's progress: the areas painted, in the order they were (undo takes the last one
 * back, and the order is the painting's replay), and the paint in hand.
 *
 * progress.bin: "MLPG", u32 version, u32 regions, i32 paint in hand (-1 none), u32 fills, then
 * u32 region per fill. Fields are append-only; a reader ignores trailing bytes.
 */
class Progress(val regionCount: Int) {
    private val painted = BooleanArray(regionCount)
    private val order = ArrayList<Int>()
    var inHand: Int = -1

    val fills: List<Int> get() = order
    val paintedCount: Int get() = order.size
    val isComplete: Boolean get() = regionCount > 0 && order.size == regionCount

    fun isPainted(region: Int) = painted[region]

    /** Paints an area; false if it already was. */
    fun paint(region: Int): Boolean {
        if (region !in 0 until regionCount || painted[region]) return false
        painted[region] = true
        order.add(region)
        return true
    }

    /** Takes the last fill back and returns its area. */
    fun undo(): Int? {
        val region = order.removeLastOrNull() ?: return null
        painted[region] = false
        return region
    }

    /** What a save writes, taken on the thread that paints. */
    class Snapshot(val bytes: ByteArray, val regionCount: Int, val paintedCount: Int, val isComplete: Boolean)

    fun snapshot() = Snapshot(encode(), regionCount, paintedCount, isComplete)

    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(4 * 5 + 4 * order.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("MLPG".toByteArray())
        b.putInt(VERSION)
        b.putInt(regionCount)
        b.putInt(inHand)
        b.putInt(order.size)
        order.forEach { b.putInt(it) }
        return b.array()
    }

    companion object {
        const val VERSION = 1

        /** Null when the bytes aren't this painting's progress (another template's, or damaged). */
        fun decode(bytes: ByteArray, regionCount: Int): Progress? = runCatching {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }.decodeToString()
            if (magic != "MLPG" || b.int < 1 || b.int != regionCount) return null
            val inHand = b.int
            val count = b.int
            if (count < 0 || count > regionCount || count.toLong() * 4 > b.remaining()) return null
            Progress(regionCount).also { p ->
                repeat(count) { if (!p.paint(b.int)) return null }
                p.inHand = inHand
            }
        }.getOrNull()
    }
}
