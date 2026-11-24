package com.paintbymoonlight.core

import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

/**
 * A paint of the palette. Its number is index + 1. [color] is sRGB (0xAARRGGBB), for pictures;
 * [p3] the template's own paint in Display P3 (a packed `android.graphics.Color` long), for the
 * canvas and the palette, which show it on a wide-gamut screen as iOS does. [name] is its
 * nickname ("Harbor Fog"; empty for a color the vocabulary doesn't name), [shade] its plain
 * color name ("Dark grayish green") and [hex] its code ("#3A5F4B").
 */
class Paint(val color: Int, val p3: Long, val name: String, val shade: String, val hex: String) {
    /** "12 · Harbor Fog", or "12 · Dark grayish green" without a nickname: the paint on screen. */
    fun numbered(index: Int) = "${index + 1} · ${name.ifEmpty { shade }}"

    /** What TalkBack says of it: "12, Harbor Fog, dark grayish green". */
    fun spoken(index: Int) =
        if (name.isEmpty()) "${index + 1}, ${shade.lowercase()}" else "${index + 1}, $name, ${shade.lowercase()}"
}

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

    /**
     * How far the segment [ax], [ay] → [bx], [by] passes from the area: 0 if it enters it, else
     * to its nearest outline (what a brush sweeping the segment needs to reach it).
     */
    fun distanceToSegment(ax: Float, ay: Float, bx: Float, by: Float): Float {
        if (contains(ax, ay) || contains(bx, by)) return 0f
        var best = Float.MAX_VALUE
        for (loop in polygons) {
            val n = loop.size / 2
            var j = n - 1
            for (i in 0 until n) {
                best = minOf(best, segmentDistance(ax, ay, bx, by, loop[2 * j], loop[2 * j + 1], loop[2 * i], loop[2 * i + 1]))
                if (best == 0f) return 0f
                j = i
            }
        }
        return best
    }

    /** How far a point lies from the area: 0 inside, else to its nearest outline. */
    fun distanceTo(x: Float, y: Float): Float {
        if (contains(x, y)) return 0f
        var best = Float.MAX_VALUE
        for (loop in polygons) {
            val n = loop.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val ax = loop[2 * j]; val ay = loop[2 * j + 1]
                val dx = loop[2 * i] - ax; val dy = loop[2 * i + 1] - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 > 0f) (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
                best = minOf(best, hypot(ax + t * dx - x, ay + t * dy - y))
                j = i
            }
        }
        return best
    }
}

/** The distance between segments p0→p1 and q0→q1: 0 where they cross. */
internal fun segmentDistance(
    p0x: Float, p0y: Float, p1x: Float, p1y: Float, q0x: Float, q0y: Float, q1x: Float, q1y: Float,
): Float {
    fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    val d1 = cross(q0x, q0y, q1x, q1y, p0x, p0y)
    val d2 = cross(q0x, q0y, q1x, q1y, p1x, p1y)
    val d3 = cross(p0x, p0y, p1x, p1y, q0x, q0y)
    val d4 = cross(p0x, p0y, p1x, p1y, q1x, q1y)
    if (((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))) return 0f
    fun pointToSegment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0f) (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
        return hypot(ax + t * dx - x, ay + t * dy - y)
    }
    return minOf(
        minOf(pointToSegment(p0x, p0y, q0x, q0y, q1x, q1y), pointToSegment(p1x, p1y, q0x, q0y, q1x, q1y)),
        minOf(pointToSegment(q0x, q0y, p0x, p0y, p1x, p1y), pointToSegment(q1x, q1y, p0x, p0y, p1x, p1y)),
    )
}

/** A number on the canvas: where, how big (canvas units), whose, and the free circle it sits in. */
class Label(val x: Float, val y: Float, val fontSize: Float, val region: Int, val radius: Float)

/**
 * A boundary between two areas ([right] is [OUTSIDE] along the canvas's border), as a flat
 * x, y array. [drawn] is false for a coloring book's color edge, which the canvas leaves out.
 */
class Edge(val left: Int, val right: Int, val drawn: Boolean, val points: FloatArray) {
    companion object {
        const val OUTSIDE = -1
    }
}

/**
 * What the canvas draws: core/Sources/MoonlightCore/Scene.swift writes it (its doc comment is
 * the format), this reads it.
 */
class Scene(
    val width: Float,
    val height: Float,
    /**
     * A coloring book: its drawing's lines stay whole over the paint and its color edges aren't
     * drawn. Otherwise classic: every edge drawn, dissolving between painted areas.
     */
    val isColoringBook: Boolean,
    val paints: List<Paint>,
    val regions: List<Region>,
    val edges: List<Edge>,
    /** Every boundary once, as one path (pictures and print). */
    val lines: Path,
    val labels: List<Label>,
    /** Line art drawn inside areas, as one path. */
    val strokes: Path,
    /** The same lines as flat x, y arrays, for the canvas. */
    val strokePoints: List<FloatArray>,
) {
    /** Each area's largest number, by area: where its paint and its sparkles start. */
    val labelOf: IntArray by lazy {
        val best = IntArray(regions.size) { -1 }
        labels.forEachIndexed { i, l ->
            if (l.region in regions.indices && (best[l.region] < 0 || labels[best[l.region]].radius < l.radius)) best[l.region] = i
        }
        best
    }

    /**
     * A small-but-typical number size (the tenth percentile), or a detail area's smallest when
     * that is smaller: the deepest zoom makes these read (iOS's `CanvasScene.smallLabelSize`).
     */
    val smallLabelSize: Float by lazy {
        val sizes = labels.map { it.fontSize }.filter { it > 0 }.sorted()
        val typical = if (sizes.isEmpty()) 1f else sizes[sizes.size / 10]
        val detail = labels.filter { it.region in regions.indices && regions[it.region].isDetail }.minOfOrNull { it.fontSize }
        minOf(typical, detail ?: typical)
    }

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
        const val VERSION = 2

        fun read(bytes: ByteArray): Scene {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }.decodeToString()
            require(magic == "MLSC") { "not a scene" }
            require(b.int == VERSION) { "scene version" }
            val width = b.float
            val height = b.float
            val book = b.int and 1 != 0

            val paints = List(b.count()) {
                val r = b.float; val g = b.float; val bl = b.float
                val p3 = Color.pack(b.float.unit(), b.float.unit(), b.float.unit(), 1f, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
                Paint(rgb(r, g, bl), p3, name = b.string(), shade = b.string(), hex = b.string())
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
            val edges = List(b.count()) {
                val left = b.int
                val right = b.int
                val drawn = b.int != 0
                val points = b.points()
                lines.addPolyline(points)
                Edge(left, right, drawn, points)
            }

            val labels = List(b.count()) { Label(b.float, b.float, b.float, b.int, b.float) }

            val strokes = Path()
            val strokePoints = List(b.count()) { b.points().also { strokes.addPolyline(it) } }

            return Scene(width, height, book, paints, regions, edges, lines, labels, strokes, strokePoints)
        }

        private fun ByteBuffer.string(): String = ByteArray(count()).also { get(it) }.decodeToString()

        private fun Float.unit() = coerceIn(0f, 1f)

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
