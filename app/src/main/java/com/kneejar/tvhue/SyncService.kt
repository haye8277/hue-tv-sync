package com.kneejar.tvhue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.IntentCompat
import com.kneejar.tvhue.capture.ScreenSampler
import com.kneejar.tvhue.hue.EntertainmentStream
import com.kneejar.tvhue.hue.HueApi
import com.kneejar.tvhue.mood.MediaWatcher
import com.kneejar.tvhue.mood.MoodEngine
import com.kneejar.tvhue.mood.NowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/** Runs the light sync in the background while other apps are on screen. */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var sampler: ScreenSampler? = null
    private var job: Job? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> shutdown()
            else -> if (job == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        sampler?.stop()
        projection?.stop()
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        if (job != null) return
        // Must be in the foreground before asking for the projection.
        startInForeground()
        stopping = false
        SyncState.status.value = SyncStatus.Starting

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val proj = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        if (proj == null) {
            fail("Screen capture permission was not granted")
            return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = shutdown()
        }, Handler(Looper.getMainLooper()))
        projection = proj

        job = scope.launch {
            try {
                run(proj)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun run(proj: MediaProjection) {
        val prefs = Prefs(this)
        val ip = prefs.bridgeIp
        val key = prefs.username
        val clientKey = prefs.clientKey
        val areaId = prefs.areaId
        if (ip == null || key == null || clientKey == null || areaId == null) {
            throw IllegalStateException("Setup isn't finished. Open the app and set it up again.")
        }

        val api = HueApi(ip)
        val area = withContext(Dispatchers.IO) { api.areas(key) }.firstOrNull { it.id == areaId }
            ?: throw IllegalStateException("Entertainment area not found. Choose \"Set up again\".")
        val stream = EntertainmentStream(ip, key, clientKey, areaId)

        try {
            withContext(Dispatchers.IO) {
                api.setStreaming(key, areaId, true)
                stream.connect()
            }
            val sampler = ScreenSampler(proj, area.channels).also {
                it.start()
                this@SyncService.sampler = it
            }
            val mood = MoodEngine(this)
            val watcher = MediaWatcher(this)
            val nowPlaying = AtomicReference<NowPlaying?>(null)

            coroutineScope {
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val np = runCatching { watcher.current() }.getOrNull()
                        np?.let { runCatching { mood.update(it) } }
                        nowPlaying.set(np)
                        delay(1_000)
                    }
                }

                val current = area.channels.associate { it.id to FloatArray(3) }
                var blackSince = 0L
                var brightSince = 0L
                var moodOn = false
                var lastStatus: SyncStatus? = null
                var reconnecting = false

                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    val np = nowPlaying.get()
                    val luma = sampler.frameLuma

                    blackSince = if (luma < BLACK_LUMA) (if (blackSince == 0L) now else blackSince) else 0L
                    brightSince = if (luma > BRIGHT_LUMA) (if (brightSince == 0L) now else brightSince) else 0L
                    moodOn = when {
                        np == null -> false
                        np.pkg in PROTECTED_APPS -> true
                        !moodOn -> blackSince != 0L && now - blackSince > 3_000
                        else -> !(brightSince != 0L && now - brightSince > 1_500)
                    }

                    val target = if (moodOn) mood.colors(area.channels, System.currentTimeMillis()) else sampler.colors
                    val alpha = if (moodOn) 0.05f else 0.35f
                    val level = prefs.brightnessFactor
                    val frame = HashMap<Int, FloatArray>(current.size)
                    for ((id, c) in current) {
                        val t = target?.get(id)
                        if (t != null) for (i in 0..2) c[i] += (t[i] - c[i]) * alpha
                        frame[id] = FloatArray(3) { i -> c[i].coerceIn(0f, 1f).pow(GAMMA) * level }
                    }

                    if (reconnecting) {
                        try {
                            withContext(Dispatchers.IO) {
                                api.setStreaming(key, areaId, true)
                                stream.connect()
                            }
                            reconnecting = false
                        } catch (e: Exception) {
                            delay(2_000)
                            continue
                        }
                    }

                    try {
                        withContext(Dispatchers.IO) { stream.send(frame) }
                    } catch (e: IOException) {
                        reconnecting = true
                        publish(SyncStatus.Running(Mode.RECONNECTING)).also { lastStatus = it }
                        delay(2_000)
                        continue
                    }

                    val status = if (moodOn) {
                        SyncStatus.Running(Mode.MOOD, np?.title?.let { "${np.appName}: $it" } ?: np?.appName.orEmpty())
                    } else {
                        SyncStatus.Running(Mode.SCREEN)
                    }
                    if (status != lastStatus) lastStatus = publish(status)

                    delay(FRAME_MS)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                stream.close()
                runCatching { api.setStreaming(key, areaId, false) }
            }
        }
    }

    private fun publish(status: SyncStatus): SyncStatus {
        if (!stopping) SyncState.status.value = status
        return status
    }

    private fun fail(message: String) {
        SyncState.status.value = SyncStatus.Error(message)
        shutdown(keepStatus = true)
    }

    private fun shutdown(keepStatus: Boolean = false) {
        if (stopping) return
        stopping = true
        val running = job
        job = null
        scope.launch {
            running?.cancelAndJoin()
            sampler?.stop()
            sampler = null
            projection?.stop()
            projection = null
            if (!keepStatus) SyncState.status.value = SyncStatus.Off
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_START = "com.kneejar.tvhue.START"
        private const val ACTION_STOP = "com.kneejar.tvhue.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        private const val FRAME_MS = 20L // 50 updates a second
        private const val GAMMA = 1.5f
        private const val BLACK_LUMA = 0.02f
        private const val BRIGHT_LUMA = 0.06f

        /** Apps whose video Android blanks out of screen capture, so they always get mood lighting. */
        private val PROTECTED_APPS = setOf(
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream",
            "com.hbo.hbonow",
            "com.hulu.livingroomplus",
            "com.apple.atve.androidtv.appletv",
            "com.cbs.ott",
            "com.peacocktv.peacockandroid",
        )

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, SyncService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_STOP))
        }
    }
}
