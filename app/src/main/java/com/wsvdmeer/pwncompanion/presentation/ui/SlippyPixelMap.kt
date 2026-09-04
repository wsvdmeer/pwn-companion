package com.wsvdmeer.pwncompanion.presentation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wsvdmeer.pwncompanion.models.CaptureEntry
import com.wsvdmeer.pwncompanion.models.GpsData
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalMono
import com.wsvdmeer.pwncompanion.utils.TileMapLoader
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

// ── Web-Mercator normalized coords (0..1), independent of zoom ────────────────
private fun lonToNx(lon: Double) = (lon + 180.0) / 360.0
private fun latToNy(lat: Double): Double {
    val r = Math.toRadians(lat)
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
}

private class Marker(val nx: Double, val ny: Double, val cap: CaptureEntry)

/**
 * Continuous slippy-map renderer with the phosphor-pixel look: a real tile pyramid drawn under a
 * live GPU pan/zoom transform, deeper tiles streaming in seamlessly as you zoom, and a screen-space
 * pixel shader on top. Smooth like gmaps/OSM; markers (catches / you) drawn crisp above the effect.
 *
 * Requires a runtime shader (API 33+) for the pixel effect — the caller falls back to the coarse
 * grid renderer on older devices.
 */
@Composable
internal fun SlippyPixelMap(
    points: List<CaptureEntry>,
    current: GpsData?,
    onCatch: (List<CaptureEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val geo = remember(points) { points.filter { it.latitude != null && it.longitude != null } }
    val markers = remember(geo) { geo.map { Marker(lonToNx(it.longitude!!), latToNy(it.latitude!!), it) } }
    val you = current?.takeIf { it.isValid() }?.let { Marker(lonToNx(it.longitude), latToNy(it.latitude), it.let { _ -> geo.firstOrNull() ?: CaptureEntry() }) }

    if (markers.isEmpty()) {
        Text("  no geolocated captures yet", color = dim, fontSize = 11.sp, fontFamily = TerminalMono, modifier = modifier)
        return
    }

    val density = LocalDensity.current
    val cellPx = with(density) { 5.dp.toPx() }

    Column(modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()

            // View state: normalized centre + DISCRETE integer zoom (stepped levels keep the pixels
            // rock-solid — no shift/pop/shimmer). Pan is continuous; a pinch accumulates until it
            // crosses a level threshold, then steps zoom by ±1. Init fits all captures.
            var centerX by remember(geo) { mutableStateOf(0.5) }
            var centerY by remember(geo) { mutableStateOf(0.5) }
            var zoom by remember(geo) { mutableStateOf(4f) }        // always an integer value
            var pinchAccum by remember(geo) { mutableStateOf(1f) }  // pinch scale since the last step
            var inited by remember(geo) { mutableStateOf(false) }
            var initCx by remember(geo) { mutableStateOf(0.5) }
            var initCy by remember(geo) { mutableStateOf(0.5) }
            var initZoom by remember(geo) { mutableStateOf(4f) }

            LaunchedEffect(geo, wPx, hPx) {
                if (inited || wPx <= 0f) return@LaunchedEffect
                val nxs = markers.map { it.nx } + (you?.let { listOf(it.nx) } ?: emptyList())
                val nys = markers.map { it.ny } + (you?.let { listOf(it.ny) } ?: emptyList())
                val spanN = maxOf(nxs.max() - nxs.min(), nys.max() - nys.min(), 3e-5)
                val fit = log2(minOf(wPx, hPx) / (256.0 * spanN * 1.35)).roundToInt().coerceIn(3, 18).toFloat()
                centerX = (nxs.min() + nxs.max()) / 2; centerY = (nys.min() + nys.max()) / 2; zoom = fit
                initCx = centerX; initCy = centerY; initZoom = zoom
                inited = true
            }

            // Loaded tiles (level/x/y → bitmap) + in-flight guard. Persist across zoom so lower levels
            // stay cached as an underlay while finer tiles stream in (seamless, no blanks).
            val tiles = remember(geo) { mutableStateMapOf<String, Bitmap>() }
            val inflight = remember(geo) { mutableSetOf<String>() }

            LaunchedEffect(geo, wPx, hPx) {
                snapshotFlow {
                    val z = zoom.toDouble()
                    val iz = floor(z).toInt().coerceIn(3, 19)
                    val pxPerN = 2.0.pow(z) * 256.0
                    val n = 1 shl iz
                    val nL = centerX - (wPx / 2) / pxPerN; val nR = centerX + (wPx / 2) / pxPerN
                    val nT = centerY - (hPx / 2) / pxPerN; val nB = centerY + (hPx / 2) / pxPerN
                    val txMin = floor(nL * n).toInt().coerceAtLeast(0); val txMax = floor(nR * n).toInt().coerceAtMost(n - 1)
                    val tyMin = floor(nT * n).toInt().coerceAtLeast(0); val tyMax = floor(nB * n).toInt().coerceAtMost(n - 1)
                    val keys = HashSet<String>()
                    for (tx in txMin..txMax) for (ty in tyMin..tyMax) keys.add("$iz/$tx/$ty")
                    keys
                }.collectLatest { keys ->
                    keys.forEach { k ->
                        if (!tiles.containsKey(k) && inflight.add(k)) {
                            launch {
                                val p = k.split("/")
                                val b = TileMapLoader.tile(context, p[0].toInt(), p[1].toInt(), p[2].toInt())
                                if (b != null) tiles[k] = b
                                inflight.remove(k)
                            }
                        }
                    }
                }
            }

            // Map-anchored pixel grid: cell size scales with zoom (≈cellPx at each tile level,
            // breathing to ~2× then halving as the next level loads) and `phase` tracks the map, so
            // pixels move/scale WITH the map instead of the map crawling through a fixed screen grid.
            val pxPerN = 2.0.pow(zoom.toDouble()) * 256.0
            val iz = floor(zoom.toDouble()).toInt().coerceIn(3, 19)
            // Marker-cluster grid: cell size scales with zoom, phase tracks the map. Catches are
            // binned into these cells (below) so they merge zoomed out and separate zoomed in.
            val cellA = (cellPx / (2.0.pow(iz.toDouble()) * 256.0) * pxPerN).toFloat().coerceAtLeast(1f)
            val phaseAX = ((wPx / 2 - centerX * pxPerN).mod(cellA.toDouble())).toFloat()
            val phaseAY = ((hPx / 2 - centerY * pxPerN).mod(cellA.toDouble())).toFloat()

            // Tile layer — rendered clean, no phosphor pixel shader. A plain dark Esri basemap reads
            // far clearer; the pixelation only amplified tile artefacts (e.g. the CARTO "API KEY
            // REQUIRED" watermark) into ghost shapes. Just clip to the map box.
            Canvas(
                Modifier.matchParentSize().graphicsLayer { clip = true }
            ) {
                drawRect(Color(0xFF02060A))
                if (!inited) return@Canvas
                // Tiles: coarsest first so finer levels land on top (seamless multi-level look).
                tiles.entries.sortedBy { it.key.substringBefore('/').toInt() }.forEach { (k, bmp) ->
                    val p = k.split("/"); val lvl = p[0].toInt(); val tx = p[1].toInt(); val ty = p[2].toInt()
                    val n = 1 shl lvl
                    val sz = pxPerN / n
                    val sx = wPx / 2 + (tx.toDouble() / n - centerX) * pxPerN
                    val sy = hPx / 2 + (ty.toDouble() / n - centerY) * pxPerN
                    if (sx + sz < 0 || sy + sz < 0 || sx > wPx || sy > hPx) return@forEach
                    val d = ceil(sz).toInt() + 1
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
                        dstSize = IntSize(d, d),
                        filterQuality = FilterQuality.Low,
                    )
                }
            }

            // Markers + gestures on top — snapped to the SAME map-anchored grid as the shader.
            Canvas(
                Modifier.matchParentSize()
                    .pointerInput(geo) {
                        detectTransformGestures { centroid, pan, gz, _ ->
                            val ppn = 2.0.pow(zoom.toDouble()) * 256.0
                            // Pan is continuous.
                            var cx = centerX - pan.x / ppn
                            var cy = centerY - pan.y / ppn
                            // Accumulate the pinch; step integer zoom levels when it crosses ~1.5×/0.67×
                            // (each step consumes a 2× factor). Keeps zoom on exact levels → stable pixels.
                            pinchAccum *= gz
                            var nz = zoom
                            while (pinchAccum >= 1.5f && nz < 19f) { nz += 1f; pinchAccum /= 2f }
                            while (pinchAccum <= 0.6667f && nz > 3f) { nz -= 1f; pinchAccum *= 2f }
                            if (nz != zoom) {
                                // Keep the point under the pinch focal point fixed across the level step.
                                val nUx = cx + (centroid.x - wPx / 2) / ppn
                                val nUy = cy + (centroid.y - hPx / 2) / ppn
                                val ppn2 = 2.0.pow(nz.toDouble()) * 256.0
                                cx = nUx - (centroid.x - wPx / 2) / ppn2
                                cy = nUy - (centroid.y - hPx / 2) / ppn2
                            }
                            centerX = cx.coerceIn(0.0, 1.0)
                            centerY = cy.coerceIn(0.0, 1.0)
                            zoom = nz
                        }
                    }
                    .pointerInput(geo) {
                        detectTapGestures(
                            onDoubleTap = { centerX = initCx; centerY = initCy; zoom = initZoom },
                            onTap = { pos ->
                                // Recompute the grid from CURRENT state (this lambda outlives the
                                // composition it was set up in), then return every catch clustered in
                                // the tapped cell (± one cell of slop for easy tapping).
                                val ppn = 2.0.pow(zoom.toDouble()) * 256.0
                                val izl = floor(zoom.toDouble()).toInt().coerceIn(3, 19)
                                val cS = (cellPx / (2.0.pow(izl.toDouble()) * 256.0) * ppn).toFloat().coerceAtLeast(1f)
                                val phX = ((wPx / 2 - centerX * ppn).mod(cS.toDouble())).toFloat()
                                val phY = ((hPx / 2 - centerY * ppn).mod(cS.toDouble())).toFloat()
                                val tcx = floor((pos.x - phX) / cS).toInt(); val tcy = floor((pos.y - phY) / cS).toInt()
                                val hit = markers.filter {
                                    val scx = wPx / 2 + (it.nx - centerX) * ppn
                                    val scy = hPx / 2 + (it.ny - centerY) * ppn
                                    abs(floor((scx - phX) / cS).toInt() - tcx) <= 1 && abs(floor((scy - phY) / cS).toInt() - tcy) <= 1
                                }.map { it.cap }
                                if (hit.isNotEmpty()) onCatch(hit)
                            },
                        )
                    }
            ) {
                if (!inited) return@Canvas
                fun cellXY(m: Marker): Pair<Int, Int> {
                    val scx = wPx / 2 + (m.nx - centerX) * pxPerN
                    val scy = hPx / 2 + (m.ny - centerY) * pxPerN
                    return floor((scx - phaseAX) / cellA).toInt() to floor((scy - phaseAY) / cellA).toInt()
                }
                // Cluster catches into the map-anchored cells: ONE phosphor pixel per occupied cell,
                // brighter when several share it. Zooming out merges them; zooming in separates them.
                val counts = HashMap<Long, Int>()
                markers.forEach {
                    val (cx, cy) = cellXY(it)
                    val k = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
                    counts[k] = (counts[k] ?: 0) + 1
                }
                counts.forEach { (k, cnt) ->
                    val cx = (k shr 32).toInt(); val cy = k.toInt()
                    val gx = cx * cellA + phaseAX; val gy = cy * cellA + phaseAY
                    if (gx < -cellA || gy < -cellA || gx > wPx || gy > hPx) return@forEach
                    drawRect(
                        if (cnt > 1) Color(0x9C, 0xFF, 0xB8) else Color(0x3D, 0xFF, 0x6E),  // brighter = cluster
                        topLeft = Offset(gx, gy), size = Size(cellA, cellA),
                    )
                }
                // You: orange, on top.
                you?.let { m ->
                    val (cx, cy) = cellXY(m)
                    val gx = cx * cellA + phaseAX; val gy = cy * cellA + phaseAY
                    if (gx >= -cellA && gy >= -cellA && gx <= wPx && gy <= hPx)
                        drawRect(Color(0xFF, 0xA5, 0x33), topLeft = Offset(gx, gy), size = Size(cellA, cellA))
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "pinch to zoom levels · drag to pan · tap a catch · double-tap to reset",
            color = dim.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = TerminalMono,
        )
    }
}
