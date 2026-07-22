package com.wsvdmeer.pwncompanion.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** A lat/lon pair for map projection. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * A composited OSM basemap for a bounding box, plus the info needed to project
 * lat/lon onto its pixels (Web Mercator / slippy-map tiles).
 */
data class MapTiles(
    val bitmap: Bitmap,
    val zoom: Int,
    /** World-pixel coordinate of the composite's top-left corner. */
    val originPxX: Double,
    val originPxY: Double,
) {
    /** Project a lat/lon to a pixel offset within [bitmap]. */
    fun project(lat: Double, lon: Double): Pair<Float, Float> {
        val n = (1 shl zoom).toDouble()
        val worldX = (lon + 180.0) / 360.0 * n * 256.0
        val latRad = Math.toRadians(lat)
        val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * 256.0
        return (worldX - originPxX).toFloat() to (worldY - originPxY).toFloat()
    }
}

/**
 * Fetches OpenStreetMap raster tiles for the bounding box of a set of points and
 * composites them into one bitmap. Tiles are cached on disk (fetch once), sent with a
 * proper User-Agent, and the caller is responsible for the "© OpenStreetMap" attribution.
 *
 * Deliberately small: at most [MAX_TILES_PER_AXIS]² tiles, so a single map view pulls a
 * handful of tiles — well within OSM's tile-usage policy for a personal companion app.
 * Returns null on any failure (no network, no tiles) so the UI can fall back to the
 * offline ASCII heatmap.
 */
object TileMapLoader {
    private const val TAG = "TileMapLoader"
    private const val TILE = 256
    private const val MAX_TILES_PER_AXIS = 3
    private const val USER_AGENT = "PwnCompanion/1.0 (pwnagotchi companion app)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun tileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)
    private fun tileY(lat: Double, z: Int): Double {
        val latRad = Math.toRadians(lat)
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl z)
    }

    suspend fun load(context: Context, points: List<GeoPoint>): MapTiles? = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext null
        try {
            var minLat = points.minOf { it.lat }
            var maxLat = points.maxOf { it.lat }
            var minLon = points.minOf { it.lon }
            var maxLon = points.maxOf { it.lon }
            // Pad the bounds so captures aren't jammed against the edge (and a single
            // point still yields a sensible neighbourhood view).
            val latM = ((maxLat - minLat) * 0.2).coerceAtLeast(0.0008)
            val lonM = ((maxLon - minLon) * 0.2).coerceAtLeast(0.0008)
            minLat -= latM; maxLat += latM; minLon -= lonM; maxLon += lonM

            // Square the bounding box in Web-Mercator space so the composite is ~square:
            // an elongated capture spread (e.g. along a road) would otherwise centre the
            // content in a wide/short bitmap and letterbox with black in the square map view.
            run {
                fun y01(lat: Double) = (1.0 - ln(tan(Math.toRadians(lat)) + 1.0 / cos(Math.toRadians(lat))) / PI) / 2.0
                val wx = (maxLon - minLon) / 360.0
                val wy = y01(minLat) - y01(maxLat)
                if (wx > wy && wy > 0) {
                    val cLat = (minLat + maxLat) / 2.0
                    val half = (maxLat - minLat) / 2.0 * (wx / wy)
                    minLat = cLat - half; maxLat = cLat + half
                } else if (wy > wx && wx > 0) {
                    val cLon = (minLon + maxLon) / 2.0
                    val half = (maxLon - minLon) / 2.0 * (wy / wx)
                    minLon = cLon - half; maxLon = cLon + half
                }
            }

            // Pick the most-detailed zoom whose tile span fits the small grid.
            var zoom = 3
            for (z in 19 downTo 3) {
                val sx = floor(tileX(maxLon, z)).toInt() - floor(tileX(minLon, z)).toInt() + 1
                val sy = floor(tileY(minLat, z)).toInt() - floor(tileY(maxLat, z)).toInt() + 1
                if (sx in 1..MAX_TILES_PER_AXIS && sy in 1..MAX_TILES_PER_AXIS) { zoom = z; break }
            }

            val x0 = floor(tileX(minLon, zoom)).toInt()
            val x1 = floor(tileX(maxLon, zoom)).toInt()
            val y0 = floor(tileY(maxLat, zoom)).toInt()
            val y1 = floor(tileY(minLat, zoom)).toInt()
            // Force a SQUARE tile window (max axis, centred). Integer tile boundaries can still
            // leave spanX≠spanY after squaring the box, and a non-square composite letterboxes
            // in the square map — so pad the short axis with real neighbouring tiles instead.
            val span = maxOf(x1 - x0 + 1, y1 - y0 + 1).coerceIn(1, MAX_TILES_PER_AXIS)
            val bx0 = x0 - (span - (x1 - x0 + 1)) / 2
            val by0 = y0 - (span - (y1 - y0 + 1)) / 2

            val composite = Bitmap.createBitmap(span * TILE, span * TILE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composite)
            var anyTile = false
            for (tx in bx0 until bx0 + span) {
                for (ty in by0 until by0 + span) {
                    val tile = fetchTile(context, zoom, tx, ty) ?: continue
                    canvas.drawBitmap(tile, ((tx - bx0) * TILE).toFloat(), ((ty - by0) * TILE).toFloat(), null)
                    tile.recycle()
                    anyTile = true
                }
            }
            if (!anyTile) {
                Log.w(TAG, "no tiles loaded (z=$zoom span=${span}x${span}) — falling back")
                return@withContext null
            }
            Log.i(TAG, "map composited z=$zoom span=${span}x${span}")
            MapTiles(composite, zoom, bx0 * TILE.toDouble(), by0 * TILE.toDouble())
        } catch (e: Exception) {
            Log.w(TAG, "tile load failed: ${e.message}")
            null
        }
    }

    private fun fetchTile(context: Context, z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        val cacheDir = File(context.cacheDir, "tilecache").apply { mkdirs() }
        // Prefix with the tile style so switching sources doesn't serve stale cached tiles.
        val f = File(cacheDir, "cd_${z}_${x}_${y}.png")
        if (f.exists() && f.length() > 0) {
            BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        }
        return try {
            // Carto "dark_nolabels": dark land, light roads, no text. Recolors to clean
            // green streets on black — far higher contrast for the phosphor look than
            // standard OSM tiles (which are light-on-light and average to green mush).
            // Free for reasonable use with "© OpenStreetMap © CARTO" attribution.
            val url = "https://a.basemaps.cartocdn.com/dark_nolabels/$z/$x/$y.png"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "tile $z/$x/$y HTTP ${resp.code} from $url")
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                runCatching { f.writeBytes(bytes) }
                Log.i(TAG, "tile $z/$x/$y fetched ${bytes.size}B")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchTile $z/$x/$y failed: ${e.message}")
            null
        }
    }
}
