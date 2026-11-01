package com.kneejar.tvhue.capture

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.kneejar.tvhue.hue.Channel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Mirrors the screen into a tiny 128x72 image and works out a colour for each light,
 * based on where that light sits in the entertainment area.
 */
class ScreenSampler(
    private val projection: MediaProjection,
    private val channels: List<Channel>,
) {
    /** Latest colour per channel id (RGB 0..1). Null until the first frame arrives. */
    @Volatile var colors: Map<Int, FloatArray>? = null
        private set

    /** Average brightness of the whole latest frame, 0..1. */
    @Volatile var frameLuma: Float = 0f
        private set

    private val thread = HandlerThread("screen-sampler").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
    private var display: VirtualDisplay? = null
    private val weights = channels.map(::weightsFor)
    private val rgb = FloatArray(WIDTH * HEIGHT * 3)
    private val vivid = FloatArray(WIDTH * HEIGHT)

    fun start() {
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                process(image)
            } finally {
                image.close()
            }
        }, handler)
        display = projection.createVirtualDisplay(
            "hue-tv-sync", WIDTH, HEIGHT, 1,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
    }

    fun stop() {
        display?.release()
        display = null
        reader.setOnImageAvailableListener(null, null)
        thread.quitSafely()
        reader.close()
    }

    private fun process(image: Image) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var lumaSum = 0f
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val o = y * rowStride + x * pixelStride
                val r = (buf.get(o).toInt() and 0xff) / 255f
                val g = (buf.get(o + 1).toInt() and 0xff) / 255f
                val b = (buf.get(o + 2).toInt() and 0xff) / 255f
                val p = y * WIDTH + x
                rgb[p * 3] = r
                rgb[p * 3 + 1] = g
                rgb[p * 3 + 2] = b
                // Vivid pixels count for more, so colours don't wash out to grey.
                vivid[p] = 0.1f + (max(r, max(g, b)) - min(r, min(g, b)))
                lumaSum += 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        frameLuma = lumaSum / (WIDTH * HEIGHT)

        val out = HashMap<Int, FloatArray>(channels.size)
        channels.forEachIndexed { ci, ch ->
            val w = weights[ci]
            var r = 0f; var g = 0f; var b = 0f; var total = 0f
            for (p in w.indices) {
                val base = w[p]
                if (base == 0f) continue
                val wt = base * vivid[p]
                r += rgb[p * 3] * wt
                g += rgb[p * 3 + 1] * wt
                b += rgb[p * 3 + 2] * wt
                total += wt
            }
            out[ch.id] = if (total > 0f) floatArrayOf(r / total, g / total, b / total) else floatArrayOf(0f, 0f, 0f)
        }
        colors = out
    }

    /** Gaussian weight around the point of the screen nearest to the light. */
    private fun weightsFor(ch: Channel): FloatArray {
        val u = ((ch.x + 1f) / 2f).coerceIn(0f, 1f)
        val v = (1f - (ch.z + 1f) / 2f).coerceIn(0f, 1f)
        val twoSigmaSq = 2f * SIGMA * SIGMA
        return FloatArray(WIDTH * HEIGHT) { p ->
            val px = (p % WIDTH + 0.5f) / WIDTH
            val py = (p / WIDTH + 0.5f) / HEIGHT
            val d2 = (px - u) * (px - u) + (py - v) * (py - v)
            val wt = exp(-d2 / twoSigmaSq)
            if (wt < 0.01f) 0f else wt
        }
    }

    private companion object {
        const val WIDTH = 128
        const val HEIGHT = 72
        const val SIGMA = 0.25f
    }
}
