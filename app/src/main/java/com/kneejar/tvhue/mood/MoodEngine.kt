package com.kneejar.tvhue.mood

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import com.kneejar.tvhue.hue.Channel
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Fallback for protected video the screen capture can't see: slow-moving ambient colours
 * taken from the show's artwork, or from the app's own banner when there is no artwork.
 */
class MoodEngine(private val context: Context) {

    @Volatile private var palette: List<FloatArray> = DEFAULT
    private var key: String? = null

    /** Picks colours for what's playing. Slow; call off the stream loop. */
    fun update(np: NowPlaying) {
        val k = "${np.pkg}|${np.title}|${np.art?.let(System::identityHashCode)}"
        if (k == key) return
        key = k
        val source = np.art ?: appArt(np.pkg)
        palette = source?.let(::extract)?.takeIf { it.isNotEmpty() } ?: DEFAULT
    }

    fun colors(channels: List<Channel>, nowMs: Long): Map<Int, FloatArray> {
        val p = palette
        val t = nowMs / 1000f
        return channels.withIndex().associate { (i, ch) ->
            val phase = (t / PERIOD_S * 2f * PI).toFloat() + i * 1.3f
            val a = p[i % p.size]
            val b = p[(i + 1) % p.size]
            val mix = (sin(phase) + 1f) / 2f * 0.6f
            val breathe = 0.85f + 0.15f * sin(phase * 0.7f + i)
            ch.id to FloatArray(3) { c -> (a[c] + (b[c] - a[c]) * mix) * breathe * LEVEL }
        }
    }

    private fun appArt(pkg: String): Bitmap? = runCatching {
        val pm = context.packageManager
        (pm.getApplicationBanner(pkg) ?: pm.getApplicationIcon(pkg)).toBitmap(160, 90)
    }.getOrNull()

    private fun extract(bitmap: Bitmap): List<FloatArray> {
        val p = Palette.from(bitmap).maximumColorCount(16).generate()
        return listOfNotNull(
            p.vibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch,
            p.lightVibrantSwatch, p.darkMutedSwatch, p.dominantSwatch,
        )
            .map { it.rgb }
            .distinct()
            .take(4)
            .map(::normalised)
    }

    // Scale so the strongest channel is full; brightness is applied separately.
    private fun normalised(color: Int): FloatArray {
        val rgb = floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        val peak = max(rgb[0], max(rgb[1], rgb[2]))
        return if (peak < 0.02f) floatArrayOf(1f, 0.55f, 0.2f) else FloatArray(3) { rgb[it] / peak }
    }

    private companion object {
        const val PERIOD_S = 20f
        const val LEVEL = 0.55f
        val DEFAULT = listOf(floatArrayOf(1f, 0.55f, 0.2f), floatArrayOf(1f, 0.4f, 0.12f))
    }
}
