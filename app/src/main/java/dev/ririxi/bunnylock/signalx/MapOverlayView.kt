// Map overlay (SignalX 1.0.9 MapOverlayView.kt, ported; Fase 5).
//
// Transparent View over the MapView. Draws:
// - solid blue line: user -> chosen target
// - dashed blue ray: the direction the phone points (from the user)
// - user point, saved points, observed cells and known towers
// Both lines stay parallel (same direction) to aim well.
//
// Towers use the PHOTO pin (dark blue pin with a white circle and the
// antenna with arcs inside, own vector tower_pin.xml): the pin's tip
// marks the exact site. One design for all towers; the connected one is
// told apart by the ring and the "CONNECTED" label, not by changing the
// pin. Its eNB number shows on zoom 14+, coloured by position source:
// estimated (white), manual (amber), observed (green). The user point is
// ONE blue piece: a ball with a tip saying where the phone looks, no
// white ring or white arrow.
//
// Labels never draw on top of each other: if two towers are stuck
// together, the second label drops a line; if it still collides, it is
// not drawn.
//
// Pointer lines are BLUE (SignalX 1.0.7, Gennis's order: no white line).
// The line to the target is solid blue #38BDF8 and the ray of where the
// phone looks is light blue #7DD3FC dashed: both blue, but different at
// a glance. Each carries a dark outline underneath (#06101F, wider) so
// it reads the same over the dark .map and over the light online tiles.
// The legend on the map says what each one is.
//
// Uncertainty circles (SignalX 1.0.8): estimated positions are not
// exact; the radius is the seed's range_m (eNB 413082: ±1000 m), drawn
// in pixels by zoom (Mercator projection). Soft fill + thin edge, so the
// margin shows without covering the map.

package dev.ririxi.bunnylock.signalx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import dev.ririxi.bunnylock.R
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import kotlin.math.PI
import kotlin.math.cos

class MapOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var userPos: LatLong? = null
    var targetPos: LatLong? = null
    var headingDeg: Float? = null
    var savedPoints: List<Pair<Double, Double>> = emptyList()
    var observedCells: List<Pair<Double, Double>> = emptyList()
    var towers: List<AntennaRow> = emptyList()
    /** eNB of the antenna the phone is connected to. Null = unknown or without position. */
    var connectedEnb: Int? = null
    /** Current map rotation in degrees (0 = north up): the heading ray and the dot tip turn with the map. */
    var mapRotationDeg: Float = 0f
    /** Provisional marker while placing an antenna by hand. */
    var placingPos: LatLong? = null
    var zoomLevel: Byte = 14
    var project: ((LatLong) -> Point?)? = null
    /** Without a downloaded .map: dark background with a grid; the lines still show. */
    var showGrid: Boolean = false

    private val targetLinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8"); strokeWidth = 6f; style = Paint.Style.STROKE
        }
    private val targetLineCasing =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06101F"); strokeWidth = 11f; style = Paint.Style.STROKE
        }
    private val headingRayPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7DD3FC"); strokeWidth = 6f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }
    private val headingRayCasing =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06101F"); strokeWidth = 11f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        }
    /** User point: ONE blue piece (ball with tip, SignalX 1.0.8). */
    private val userDotPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8"); style = Paint.Style.FILL
        }
    private val pointPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4ADE80"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
    private val cellPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FBBF24"); style = Paint.Style.FILL
        }
    private val gridPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E4D38"); strokeWidth = 2f; style = Paint.Style.STROKE
        }
    private val towerRingConnected =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8"); style = Paint.Style.STROKE; strokeWidth = 3f
        }
    private val placingPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FBBF24"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
    private val uncertaintyFill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDF6F2"); alpha = 18; style = Paint.Style.FILL
        }
    private val uncertaintyStroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDF6F2"); alpha = 110; style = Paint.Style.STROKE; strokeWidth = 2f
        }

    private fun labelPaint(
        color: Int,
        size: Float,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = Typeface.MONOSPACE
    }

    private val labelHalo =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06101F"); textSize = 26f; typeface = Typeface.MONOSPACE
        }
    private val labelHaloSmall =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06101F"); textSize = 22f; typeface = Typeface.MONOSPACE
        }

    /** Antenna pin as in Gennis's photo (SignalX 1.0.8): dark blue pin with
     *  a white circle and the antenna with arcs inside. It is an OWN
     *  VECTOR (tower_pin.xml), nothing drawn in code: here it is only
     *  rasterized once. The pin is 28x34 dp: its tip (14,34) marks the
     *  exact site and the bubble sits 22 dp above it. */
    private var towerBmp: Bitmap? = null

    private fun towerBitmap(): Bitmap {
        towerBmp?.let { return it }
        val density = resources.displayMetrics.density
        val w = (28f * density).toInt().coerceAtLeast(28)
        val h = (34f * density).toInt().coerceAtLeast(34)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        try {
            val d = context.getDrawable(R.drawable.tower_pin)?.mutate()
            if (d != null) {
                d.setBounds(0, 0, w, h)
                d.draw(c)
            }
        } catch (_: Exception) {
        }
        towerBmp = bmp
        return bmp
    }

    private fun towerLabelColor(
        source: AntennaSource,
        connected: Boolean,
    ): Int =
        when {
            connected -> Color.parseColor("#38BDF8")
            source == AntennaSource.MANUAL -> Color.parseColor("#FBBF24")
            source == AntennaSource.OBSERVED -> Color.parseColor("#4ADE80")
            else -> Color.parseColor("#EDF6F2")
        }

    /** Uncertainty radius in pixels at a zoom (Mercator: pixels per metre shrink with cos(latitude)). */
    private fun uncertaintyRadiusPx(
        rangeM: Int,
        lat: Double,
        zoom: Int,
    ): Float {
        val scale = (256 * (1 shl zoom)).toDouble() / (2 * PI * 6_371_000.0)
        return (rangeM * scale * cos(Math.toRadians(lat))).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showGrid) {
            val step = 110f
            var x = 0f
            while (x <= width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                x += step
            }
            var y = 0f
            while (y <= height) {
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                y += step
            }
        }
        val proj = project ?: return
        val dd = resources.displayMetrics.density
        // Uncertainty circles: each estimated tower carries its margin in
        // metres (seed range_m) drawn on the map. Before the pins, so the
        // pin stays on top.
        for (t in towers) {
            val lat = t.lat ?: continue
            val lon = t.lon ?: continue
            if (t.source != AntennaSource.ESTIMATED || t.rangeM <= 0) continue
            val tp = proj(LatLong(lat, lon)) ?: continue
            val r = uncertaintyRadiusPx(t.rangeM, lat, zoomLevel.toInt())
            if (r > 4f) {
                canvas.drawCircle(tp.x.toFloat(), tp.y.toFloat(), r, uncertaintyFill)
                canvas.drawCircle(tp.x.toFloat(), tp.y.toFloat(), r, uncertaintyStroke)
            }
        }
        // Known towers: drawn even without GPS. The pin anchors by its
        // tip (14,34 dp of the bitmap) on the exact site; the bubble sits
        // 22 dp above the site.
        val drawnLabels = mutableListOf<RectF>()
        for (t in towers) {
            val lat = t.lat ?: continue
            val lon = t.lon ?: continue
            val tp = proj(LatLong(lat, lon)) ?: continue
            val x = tp.x.toFloat()
            val y = tp.y.toFloat()
            val connected = connectedEnb != null && t.enb == connectedEnb
            val bmp = towerBitmap()
            canvas.drawBitmap(bmp, x - 14f * dd, y - 34f * dd, null)
            if (connected) {
                val up = userPos?.let { proj(it) }
                val far =
                    up == null ||
                        kotlin.math.hypot(x - up.x.toFloat(), (y - 22f * dd) - up.y.toFloat()) > 34f * dd
                if (far) canvas.drawCircle(x, y - 22f * dd, 15f * dd, towerRingConnected)
            }
            // eNB number on zoom 14+, with a dark halo so it reads.
            if (zoomLevel >= 14) {
                val label = t.enb.toString()
                val paint = labelPaint(towerLabelColor(t.source, connected), 26f)
                val tw = paint.measureText(label)
                var ly = y - 22f * dd + 9f
                val lx = x + 16f * dd
                var rect = RectF(lx, ly - 26f, lx + tw, ly + 8f)
                var shifts = 0
                while (shifts < 2 && drawnLabels.any { RectF.intersects(it, rect) }) {
                    ly += 30f
                    rect = RectF(lx, ly - 26f, lx + tw, ly + 8f)
                    shifts++
                }
                if (drawnLabels.none { RectF.intersects(it, rect) }) {
                    canvas.drawText(label, lx + 2f, ly, labelHalo)
                    canvas.drawText(label, lx, ly, paint)
                    drawnLabels.add(rect)
                    if (connected) {
                        val sub = "CONNECTED"
                        val subPaint = labelPaint(Color.parseColor("#38BDF8"), 22f)
                        val sw = subPaint.measureText(sub)
                        val subRect = RectF(lx, ly + 8f, lx + sw, ly + 30f)
                        if (drawnLabels.none { RectF.intersects(it, subRect) }) {
                            canvas.drawText(sub, lx + 2f, ly + 22f, labelHaloSmall)
                            canvas.drawText(sub, lx, ly + 22f, subPaint)
                            drawnLabels.add(subRect)
                        }
                    }
                }
            }
        }
        // Saved points.
        for ((lat, lon) in savedPoints) {
            val pp = proj(LatLong(lat, lon)) ?: continue
            canvas.drawCircle(pp.x.toFloat(), pp.y.toFloat(), 10f, pointPaint)
        }
        // Observed cells.
        for ((lat, lon) in observedCells) {
            val cp = proj(LatLong(lat, lon)) ?: continue
            canvas.drawCircle(cp.x.toFloat(), cp.y.toFloat(), 8f, cellPaint)
        }
        // Mark of the chosen point (visible without GPS too).
        targetPos?.let { t ->
            val tp = proj(t) ?: return@let
            canvas.drawCircle(tp.x.toFloat(), tp.y.toFloat(), 12f, pointPaint)
        }
        // Provisional marker while placing an antenna by hand.
        placingPos?.let { p ->
            val pp = proj(p) ?: return@let
            canvas.drawCircle(pp.x.toFloat(), pp.y.toFloat(), 16f, placingPaint)
            val bmp = towerBitmap()
            canvas.drawBitmap(bmp, pp.x.toFloat() - 14f * dd, pp.y.toFloat() - 34f * dd, null)
        }
        val user = userPos ?: return
        val userPt = proj(user) ?: return
        val ux = userPt.x.toFloat()
        val uy = userPt.y.toFloat()
        val len = (height * 0.45f).coerceAtLeast(120f)

        // Ray of where the phone looks: dashed light blue. Its angle on
        // screen is heading - map rotation: when the map is turned, north
        // is no longer up and the ray has to turn with the map to keep
        // pointing at the same place. The line starts at the EDGE of the
        // blue point, not its center: no knot of circles around the point.
        headingDeg?.let { h ->
            val rad = Math.toRadians((h - mapRotationDeg).toDouble())
            val sx = (ux + Math.sin(rad) * 8f * dd).toFloat()
            val sy = (uy - Math.cos(rad) * 8f * dd).toFloat()
            val ex = ux + (Math.sin(rad) * len).toFloat()
            val ey = uy - (Math.cos(rad) * len).toFloat()
            canvas.drawLine(sx, sy, ex, ey, headingRayCasing)
            canvas.drawLine(sx, sy, ex, ey, headingRayPaint)
        }
        // Line to the chosen point: solid blue. It goes from projected
        // point to projected point, so the map rotation is already in it
        // on its own. Starts at the edge of the blue point and ends at
        // the edge of the target pin, not its center.
        targetPos?.let { t ->
            val tp = proj(t) ?: return@let
            val dx = tp.x.toFloat() - ux
            val dy = tp.y.toFloat() - uy
            val dist = kotlin.math.hypot(dx, dy)
            if (dist > 30f * dd) {
                val sx = ux + dx / dist * 8f * dd
                val sy = uy + dy / dist * 8f * dd
                val ex = tp.x.toFloat() - dx / dist * 14f * dd
                val ey = tp.y.toFloat() - dy / dist * 14f * dd
                canvas.drawLine(sx, sy, ex, ey, targetLineCasing)
                canvas.drawLine(sx, sy, ex, ey, targetLinePaint)
            }
        }
        // User point (SignalX 1.0.8): ONE blue piece — ball with a tip
        // saying where it looks, no white ring or white arrow. The tip is
        // born inside the ball and sticks out 13 dp: ball and triangle
        // read as one blue lump. Without a heading there is no tip, only
        // the ball.
        canvas.drawCircle(ux, uy, 7f * dd, userDotPaint)
        headingDeg?.let { h ->
            val ar = Math.toRadians((h - mapRotationDeg).toDouble())
            val ax = (ux + Math.sin(ar) * 13f * dd).toFloat()
            val ay = (uy - Math.cos(ar) * 13f * dd).toFloat()
            val bx = (ux + Math.sin(ar) * 2f * dd).toFloat()
            val by = (uy - Math.cos(ar) * 2f * dd).toFloat()
            val px = (Math.cos(ar) * 5f * dd).toFloat()
            val py = (Math.sin(ar) * 5f * dd).toFloat()
            val arrow = Path()
            arrow.moveTo(ax, ay)
            arrow.lineTo(bx - px, by - py)
            arrow.lineTo(bx + px, by + py)
            arrow.close()
            canvas.drawPath(arrow, userDotPaint)
        }
    }
}
