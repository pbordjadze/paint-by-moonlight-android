package com.paintbymoonlight.core

import android.graphics.Path
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A paint of the palette: its sRGB color (0xAARRGGBB) and nickname. Its number is index + 1. */
class Paint(val color: Int, val name: String)

/**
 * An area to paint. [path] is its outline in canvas units, holes included (even-odd);
 * [polygons] the same loops as flat x, y arrays, for hit testing.
 */
class Region(
    val paint: Int,
    val area: Float,
    val isDetail: Boolean,
    val bounds: RectF,
    val path: Path,
    val polygons: List<FloatArray>,
) {
    /** Even-odd point-in-polygon over every loop. */
    fun contains(x: Float, y: Float): Boolean {
        if (!bounds.contains(x, y)) return false
        var inside = false
        for (loop in polygons) {
            val n = loop.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = loop[2 * i]; val yi = loop[2 * i + 1]
                val xj = loop[2 * j]; val yj = loop[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }
}

/** A number on the canvas: where, how big (canvas units) and whose. */
class Label(val x: Float, val y: Float, val fontSize: Float, val region: Int)

/**
 * What the canvas draws: core/Sources/MoonlightCore/Scene.swift writes it (its doc comment is
 * the format), this reads it.
 */
class Scene(
    val width: Float,
    val height: Float,
    val paints: List<Paint>,
    val regions: List<Region>,
    /** Every boundary once, as one path. */
    val lines: Path,
    val labels: List<Label>,
    /** Line art drawn inside areas, as one path. */
    val strokes: Path,
    /** [lines] and [strokes] as their polylines (flat x, y), for drawing only the part a printed sheet shows. */
    val linePolylines: List<FloatArray> = emptyList(),
    val strokePolylines: List<FloatArray> = emptyList(),
) {
    /** Areas of each paint, by paint index. */
    val regionsByPaint: List<IntArray> by lazy {
        val lists = List(paints.size) { ArrayList<Int>() }
        regions.forEachIndexed { i, r -> if (r.paint < lists.size) lists[r.paint].add(i) }
        lists.map { it.toIntArray() }
    }

    /** The area under a point, the smallest when areas nest (a hole's island). */
    fun regionAt(x: Float, y: Float): Int? {
        var best: Int? = null
        var bestArea = Float.MAX_VALUE
        regions.forEachIndexed { i, r ->
            if (r.area < bestArea && r.contains(x, y)) {
                best = i; bestArea = r.area
            }
        }
        return best
    }

    companion object {
        const val VERSION = 1

        fun read(bytes: ByteArray): Scene {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }.decodeToString()
            require(magic == "MLSC") { "not a scene" }
            require(b.int == VERSION) { "scene version" }
            val width = b.float
            val height = b.float

            val paints = List(b.count()) {
                val r = b.float; val g = b.float; val bl = b.float
                val name = ByteArray(b.count()).also { b.get(it) }.decodeToString()
                Paint(rgb(r, g, bl), name)
            }

            val regions = List(b.count()) {
                val paint = b.int
                val area = b.float
                val flags = b.int
                val bounds = RectF(b.float, b.float, b.float + 1, b.float + 1)
                val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
                val polygons = List(b.count()) {
                    val loop = b.points()
                    if (loop.size >= 6) {
                        path.moveTo(loop[0], loop[1])
                        for (i in 1 until loop.size / 2) path.lineTo(loop[2 * i], loop[2 * i + 1])
                        path.close()
                    }
                    loop
                }
                Region(paint, area, flags and 1 != 0, bounds, path, polygons)
            }

            val lines = Path()
            val linePolylines = List(b.count()) { b.points().also { lines.addPolyline(it) } }

            val labels = List(b.count()) { Label(b.float, b.float, b.float, b.int) }

            val strokes = Path()
            val strokePolylines = List(b.count()) { b.points().also { strokes.addPolyline(it) } }

            return Scene(width, height, paints, regions, lines, labels, strokes, linePolylines, strokePolylines)
        }

        private fun ByteBuffer.count(): Int {
            val n = int
            require(n in 0..remaining()) { "corrupt scene" }
            return n
        }

        private fun ByteBuffer.points(): FloatArray {
            val n = count()
            require(n.toLong() * 8 <= remaining()) { "corrupt scene" }
            return FloatArray(n * 2) { float }
        }

        private fun Path.addPolyline(p: FloatArray) {
            if (p.size < 4) return
            moveTo(p[0], p[1])
            for (i in 1 until p.size / 2) lineTo(p[2 * i], p[2 * i + 1])
        }

        private fun rgb(r: Float, g: Float, b: Float): Int {
            fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
    }
}
