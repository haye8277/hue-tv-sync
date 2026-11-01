package com.kneejar.tvhue.mood

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import java.io.InputStream
import java.net.URL

data class NowPlaying(
    val pkg: String,
    val appName: String,
    val title: String?,
    val art: Bitmap?,
)

/** Reports which app is playing media right now, with its title and artwork when available. */
class MediaWatcher(private val context: Context) {

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private var artUri: String? = null
    private var artFromUri: Bitmap? = null

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Blocking (may download artwork). Call from a background dispatcher. */
    fun current(): NowPlaying? {
        val active = try {
            sessions?.getActiveSessions(listener) ?: return null
        } catch (e: SecurityException) {
            return null
        }
        val controller = active.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: return null
        val md = controller.metadata
        val pkg = controller.packageName
        return NowPlaying(
            pkg = pkg,
            appName = appName(pkg),
            title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            art = md?.let(::art),
        )
    }

    private fun appName(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun art(md: MediaMetadata): Bitmap? {
        for (key in BITMAP_KEYS) {
            md.getBitmap(key)?.let { return softwareCopy(it) }
        }
        val uri = URI_KEYS.firstNotNullOfOrNull { md.getString(it)?.takeIf(String::isNotBlank) } ?: return null
        if (uri != artUri) {
            artUri = uri
            artFromUri = runCatching { load(uri) }.getOrNull()
        }
        return artFromUri
    }

    private fun load(uri: String): Bitmap? {
        val parsed = Uri.parse(uri)
        val input: InputStream = when (parsed.scheme) {
            "http", "https" -> URL(uri).openConnection().apply {
                connectTimeout = 4_000
                readTimeout = 4_000
            }.getInputStream()
            "content", "android.resource", "file" -> context.contentResolver.openInputStream(parsed)
            else -> null
        } ?: return null
        return input.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
    }

    // Palette needs to read pixels, which hardware bitmaps don't allow.
    private fun softwareCopy(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b

    private companion object {
        val BITMAP_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        val URI_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )
    }
}
